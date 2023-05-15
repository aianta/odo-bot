package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.neo4j.driver.Values.parameters;

public class Neo4JUtils {

    private static final Logger log = LoggerFactory.getLogger(Neo4JUtils.class);
    private final Driver driver;

    public Neo4JUtils(String uri, String user, String password){
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user,password));
    }

    public UUID createState(UUID modelId){
        UUID id = UUID.randomUUID();
        createState(id, modelId);
        return id;
    }

    public void createState(UUID id, UUID modelId){
        var stmt = "CREATE (n:ApplicationState {id:$id, model:$model}) RETURN n";
        var query = new Query(stmt, parameters("id", id.toString(), "model", modelId.toString()));
        write(query);
    }

    public void createTimelineEntity(TimelineEntity e, Timeline t, UUID modelId){
        var index = t.indexOf(e);
        log.info("entity {} index: {}", e.symbol(),  index);
        log.info("timeline: {}", t.toJson().encodePrettily());
        var stmt = "CREATE (n:TimelineEntity {symbol:$symbol, terms: $terms, timeline:$tId, index:$index, model:$model }) RETURN n";
        //TODO-migrate to post pipeline api data structures
//        var query = new Query(stmt, parameters("symbol", e.symbol(), "terms", e.terms(), "tId", t.getId().toString(), "index", index, "model",modelId.toString()));
//        write(query);
    }

    public void createEntitiesForTimeline(Timeline t, UUID modelId){
        t.forEach(entity -> createTimelineEntity(entity, t, modelId));
    }

    public void connectStateToEntity(UUID stateId, UUID timelineId, int timelineIndex){
        var stmt = "MATCH (s:ApplicationState {id:$sId}), (e:TimelineEntity {timeline:$tId, index:$index}) CREATE (s)-[r:causes]->(e) RETURN r";
        var query = new Query(stmt, parameters("sId", stateId.toString(), "tId", timelineId.toString(), "index", timelineIndex));
        write(query);
    }

    public void connectEntityToState(UUID timelineId, int timelineIndex, UUID stateId){
        var stmt = "MATCH (e:TimelineEntity {timeline:$tId, index:$index}), (s:ApplicationState {id:$sId}) CREATE (e)-[r:causes]->(s) RETURN r";
        var query = new Query(stmt, parameters("tId", timelineId.toString(), "index", timelineIndex, "sId", stateId.toString()));
        write(query);
    }



    public void write(Query query){
        try(var session = driver.session()){
            session.executeWrite(tx->{
                tx.run(query);
                return 0;
            });
        }
    }
}
