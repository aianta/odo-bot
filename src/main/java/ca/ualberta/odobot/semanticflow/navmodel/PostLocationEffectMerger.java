package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.neo4j.graphdb.*;

import java.util.*;
import java.util.stream.Collectors;

public class PostLocationEffectMerger {

    private static final Logger log = LoggerFactory.getLogger(PostLocationEffectMerger.class);

    private final GraphDatabaseService db;

    public PostLocationEffectMerger(GraphDB graphDB){
        this.db = graphDB.db;
    }

    public void doPass(){


            //Retrieve a manifest of all location nodes.
            try(
                Transaction tx = db.beginTx();
                Result result = tx.execute("MATCH (n:LocationNode) return n;");
                ResourceIterator<Node> locationNodes = result.columnAs("n");
            ){

                //For every location node, find EffectNodes that directly follow it.
                locationNodes.forEachRemaining(locationNode->{

                    UUID nodeId = UUID.fromString((String)locationNode.getProperty("id"));

                    //Merge all EffectNodes directly following a location into a single collapsed effect node.
                    //To do this, we capture all relevant incoming and outgoing relationships as well as instances belonging to the effect nodes, and use that information to create the merged/collapsed node.
                    Set<Node> effectNodes = locationNode.getRelationships(Direction.OUTGOING, RelationshipType.withName("NEXT"))
                            .stream()
                            .filter(relationship -> relationship.getEndNode().hasLabel(Label.label("EffectNode")))
                            .map(relationship -> relationship.getEndNode())
                            .collect(Collectors.toSet());

                    //If there were no effect nodes or a single effect node, then there is nothing to merge for this location node.
                    if(effectNodes.size() <= 1){
                        log.info("No or single effect node immediately follow LocationNode {}:{}", (String)locationNode.getProperty("id"), (String)locationNode.getProperty("path"));
                        return;
                    }

                        //Collect incoming nodes, outgoing nodes, and instances from all the effects surrounding the location node.
                        Set<Node> incomingNodes  = new HashSet<>();
                        Set<Node> outgoingNodes  = new HashSet<>();
                        Set<String> instances = new HashSet<>();

                        effectNodes.forEach(effectNode->{

                            //Find all nodes with an incoming NEXT edge to this effect node.
                            incomingNodes.addAll(effectNode.getRelationships(Direction.INCOMING, RelationshipType.withName("NEXT")).stream()
                            //        .filter(relationship -> !relationship.getEndNode().hasLabel(Label.label("LocationNode")))
                                    .map(relationship -> relationship.getStartNode())
                                    .collect(Collectors.toSet()));

                            //Find all nodes to which this effect node has an outgoing NEXT edge.
                            outgoingNodes.addAll(effectNode.getRelationships(Direction.OUTGOING, RelationshipType.withName("NEXT")).stream()
                                    .map(relationship -> relationship.getEndNode())
                                    .collect(Collectors.toSet()));

                            //Get all the instances associated with this effect node
                            String [] _instances = (String[]) effectNode.getProperty("instances");
                            instances.addAll(Arrays.stream(_instances).toList());


                            //Remove all relationships from this effect node
                            effectNode.getRelationships().forEach(relationship -> relationship.delete());

                            //Remove the effect node
                            effectNode.delete();

                        });




                        //Create the merged node
                        Node mergedEffectNode = tx.createNode(Label.label("EffectNode"), Label.label("CollapsedEffectNode"));
                        mergedEffectNode.setProperty("id", UUID.randomUUID().toString());
                        mergedEffectNode.setProperty("instances", instances.toArray(new String [0]));

                        //Stitch together the outgoing relationships
                        outgoingNodes.forEach(outNode->{
                            mergedEffectNode.createRelationshipTo(outNode, RelationshipType.withName("NEXT"));
                        });

                        //Stitch together the incoming relationships.
                        incomingNodes.forEach(inNode->{
                            try{
                                inNode.createRelationshipTo(mergedEffectNode, RelationshipType.withName("NEXT"));
                            }catch (NotFoundException e){
                                log.warn("inNode {} has already been deleted and cannot be reconnected to the merged node!", inNode.getElementId());
                            }

                        });

                        log.info("Created merged effect node: {} for LocationNode {}:{}", (String)mergedEffectNode.getProperty("id"), (String)locationNode.getProperty("id"), (String)locationNode.getProperty("path"));









                });

                tx.commit();
            }


    }

}
