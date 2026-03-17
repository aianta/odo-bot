package ca.ualberta.odobot.taskplanner;

import apoc.util.collection.Iterables;
import ca.ualberta.odobot.sqlite.SqliteService;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.BranchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * https://neo4j.com/docs/java-reference/current/traversal-framework/traversal-framework-java-api/#traversal-java-api-pathexpander
 */

public class TrajectoryDistanceBeamPathExpander implements PathExpander {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryDistanceBeamPathExpander.class);
    private static final String SQLITE_PATH = "jdbc:sqlite:odobot.db";

    private int beamWidth;
    private String targetNodeId;

    private Connection conn;

    public TrajectoryDistanceBeamPathExpander( String targetNodeId, int beamWidth) {
        this.beamWidth = beamWidth;
        this.targetNodeId = targetNodeId;

        try{
            this.conn = DriverManager.getConnection(SQLITE_PATH);

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public ResourceIterable<Relationship> expand(Path path, BranchState branchState) {

        Node endNode = path.endNode();

        ResourceIterable<Relationship> relationships = endNode.getRelationships(Direction.OUTGOING, RelationshipType.withName("NEXT"));
        Map<Relationship, Double> candidates = new HashMap<>();

        //Go through the outgoing edges from the last node in the path being considered.
        for (Relationship relationship : relationships) {
            String nextNodeId = (String)relationship.getEndNode().getProperty("id");

            //Compute the estimated distance between the node at the end of a candidate edge and the target node.
            Double estimatedDistance = estimateDistance(nextNodeId, targetNodeId);

            if (estimatedDistance != null) {
                log.info("Estimated distance to target for node {} is {}", nextNodeId, estimatedDistance);
                candidates.put(relationship, estimatedDistance);
            }else{
                log.info("No distance estimate from {} to {}", nextNodeId, targetNodeId );
            }

        }

        //Sort candidates by their estimated distance to the target node in ascending order. Shortest distance first.
        List<Relationship> sortedCandidates = candidates.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(beamWidth)
                .peek(entry -> {
                    log.info("Estimated distance between candidate node [{}] and target node [{}] is: {}", entry.getKey().getEndNode().getProperty("id"), targetNodeId, entry.getValue());
                })
                .map(Map.Entry::getKey)
                .toList();


        return Iterables.asResourceIterable(sortedCandidates);
    }

    private Double estimateDistance(String sourceNodeId, String targetNodeId){

        try{
            String sql = """
                WITH
                     src_avg_index AS (
                         SELECT avg(event_index_in_trajectory) as value from event_node_index where node_id = ? and trajectory_id 
                            IN (
                                SELECT trajectory_id from event_node_index where node_id = ? and trajectory_id 
                                    IN (
                                        SELECT trajectory_id from event_node_index where node_id = ?
                                    )
                            ) 
                     ), 
                     tgt_avg_index AS (
                        SELECT avg(event_index_in_trajectory) as value from event_node_index where node_id = ? and trajectory_id 
                        IN (
                            SELECT trajectory_id from event_node_index where node_id = ? and trajectory_id 
                                IN (
                                    SELECT trajectory_id from event_node_index where node_id = ?
                                )
                        ) 
                     )
                    
                    SELECT ABS(src_avg_index.value - tgt_avg_index.value) as distance FROM src_avg_index, tgt_avg_index;
                """;

            var pstmt = conn.prepareStatement(sql);
            pstmt.setString(1,  sourceNodeId);
            pstmt.setString(2, sourceNodeId);
            pstmt.setString(3, targetNodeId);
            pstmt.setString(4, targetNodeId);
            pstmt.setString(5, sourceNodeId);
            pstmt.setString(6, targetNodeId);

            ResultSet rs = pstmt.executeQuery();
            rs.next();
            Double distance = rs.getDouble("distance");
            if(rs.wasNull()){
                return null;
            }else{
                return distance;
            }



        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public PathExpander reverse() {
        throw new RuntimeException("Not needed for MonoDirectional Traversal Framework");
    }

    public void cleanUp(){
        try {
            this.conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
