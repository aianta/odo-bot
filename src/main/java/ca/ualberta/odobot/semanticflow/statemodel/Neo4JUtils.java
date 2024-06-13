package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.*;

import ca.ualberta.odobot.semanticflow.navmodel.nodes.*;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.exceptions.NoSuchRecordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import static org.neo4j.driver.Values.parameters;

public class Neo4JUtils {

    private static final Logger log = LoggerFactory.getLogger(Neo4JUtils.class);
    private final Driver driver;

    private static final String databaseName = "neo4j";

    public HashMap<Effect, UUID> effectMap = new HashMap<>();

    public Neo4JUtils(String uri, String user, String password){
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user,password));
    }

    public UUID createState(UUID modelId){
        UUID id = UUID.randomUUID();
        createState(id, modelId);
        return id;
    }

    public LocationNode getOrCreateLocation(String path, Timeline timeline){

        LocationNode result = getLocationNode(path);

        if(result == null){
            //No existing location node found, create one.
            LocationNode newLocation = new LocationNode();
            newLocation.setId(UUID.randomUUID());
            newLocation.setPath(path);
            newLocation.setInstances(Set.of(timeline.getId().toString()));
            result = newLocation;
        }else{
            //If a location node was found, update its list of instances.
            result.getInstances().add(timeline.getId().toString());
        }

        final LocationNode location = result;

        //Write the changes back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            HashMap<String,Object> props = new HashMap<>();
            props.put("path", location.getPath());
            props.put("id", location.getId().toString());
            props.put("instances", location.getInstances());

            session.executeWrite(tx->{
                var stmt = "MERGE (n:LocationNode {path:$path}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
                var query = new Query(stmt, parameters("path", location.getPath(), "props", props));
                tx.run(query);
                return 0;
            });

        }

        return result;

    }

    public LocationNode getLocationNode(String path){
        var stmt = "MATCH (n:LocationNode {path:$path}) return n;";
        var query = new Query(stmt, parameters("path", path));

        return readNode(query, LocationNode.class);
    }

    public void processNetworkEvent(Timeline timeline, NetworkEvent networkEvent){
        var index = timeline.indexOf(networkEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        log.info("Processing data entry {}", entityTimelineId);

        APINode apiNode = getAPINode(networkEvent.getPath(), networkEvent.getMethod());

        if(apiNode == null){
            //If no data entry node could be found, let's create one
            APINode newApiNode = new APINode();
            newApiNode.setPath(networkEvent.getPath());
            newApiNode.setMethod(networkEvent.getMethod());
            newApiNode.setId(UUID.randomUUID());
            newApiNode.setInstances(Set.of(entityTimelineId));
            apiNode = newApiNode;
        }else{
            //If an api node was found, update its list of instances
            apiNode.getInstances().add(entityTimelineId);
        }

        final APINode finalApiNode = apiNode;

        //Write the changes to the data entry node back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){


            HashMap<String, Object> props = new HashMap<>();
            props.put("path", finalApiNode.getPath());
            props.put("method", finalApiNode.getMethod());
            props.put("id", finalApiNode.getId().toString());
            props.put("instances", finalApiNode.getInstances());

            session.executeWrite(tx->{
                var stmt = "MERGE (n:APINode { path:$path, method:$method }) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
                var query = new Query(stmt, parameters("path", finalApiNode.getPath(), "method", finalApiNode.getMethod() , "props", props));
                tx.run(query);
                return 0;
            });
        }

    }


    public void processApplicationLocationChange(Timeline timeline, ApplicationLocationChange applicationLocationChange){
        var index = timeline.indexOf(applicationLocationChange);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        log.info("Processing application location change {}", entityTimelineId);
        getOrCreateLocation(applicationLocationChange.getToPath(), timeline);


//
//
//
//        ApplicationLocationChangeNode applicationLocationChangeNode = getApplicationLocationChangeNode(applicationLocationChange.getFromPath(), applicationLocationChange.getToPath());
//
//        if(applicationLocationChangeNode == null){
//            //If no application location change node could be found, let's create a new one
//            ApplicationLocationChangeNode newApplicationLocationChangeNode = new ApplicationLocationChangeNode();
//            newApplicationLocationChangeNode.setId(UUID.randomUUID());
//            newApplicationLocationChangeNode.setTo(applicationLocationChange.getToPath());
//            newApplicationLocationChangeNode.setFrom(applicationLocationChange.getFromPath());
//            newApplicationLocationChangeNode.setInstances(Set.of(entityTimelineId));
//            applicationLocationChangeNode = newApplicationLocationChangeNode;
//        }else{
//            //If a application location change node was found, update its list of instances
//            applicationLocationChangeNode.getInstances().add(entityTimelineId);
//        }
//
//        final ApplicationLocationChangeNode finalApplicationLocationChangeNode = applicationLocationChangeNode;
//        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
//            HashMap<String,Object> props = new HashMap<>();
//            props.put("id", finalApplicationLocationChangeNode.getId().toString());
//            props.put("from", finalApplicationLocationChangeNode.getFrom());
//            props.put("to", finalApplicationLocationChangeNode.getTo());
//            props.put("instances", finalApplicationLocationChangeNode.getInstances());
//
//            session.executeWrite(tx->{
//                var stmt = "MERGE (n:ApplicationLocationChangeNode {from:$from, to:$to}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
//                var query = new Query(stmt, parameters("from", finalApplicationLocationChangeNode.getFrom(), "to", finalApplicationLocationChangeNode.getTo(), "props", props));
//                tx.run(query);
//                return 0;
//            });
//
//        }

    }

    public void processDataEntry(Timeline timeline, DataEntry dataEntry){
        var index = timeline.indexOf(dataEntry);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        log.info("Processing data entry {}", entityTimelineId);

        DataEntryNode dataEntryNode = getDataEntryNode(dataEntry.xpath());

        if(dataEntryNode == null){
            //If no data entry node could be found, let's create one
            DataEntryNode newDataEntryNode = new DataEntryNode();
            newDataEntryNode.setId(UUID.randomUUID());
            newDataEntryNode.setXpath(dataEntry.xpath());
            newDataEntryNode.setInstances(Set.of(entityTimelineId));
            dataEntryNode = newDataEntryNode;
        }else{
            //If a data entry node was found, update its list of instances
            dataEntryNode.getInstances().add(entityTimelineId);
        }

        final DataEntryNode finalDataEntryNode = dataEntryNode;

        //Write the changes to the data entry node back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", finalDataEntryNode.getXpath());
            props.put("id", finalDataEntryNode.getId().toString());
            props.put("instances", finalDataEntryNode.getInstances());

            session.executeWrite(tx->{
                var stmt = "MERGE (n:DataEntryNode { xpath:$xpath }) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
                var query = new Query(stmt, parameters("xpath", finalDataEntryNode.getXpath(), "props", props));
                tx.run(query);
                return 0;
            });
        }

    }

    public void processClickEvent(Timeline timeline, ClickEvent clickEvent){
        var index  = timeline.indexOf(clickEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        log.info("Processing click event {}", entityTimelineId);

        String clickText = clickEvent.getTriggerElement() != null?clickEvent.getTriggerElement().ownText():"";
        String clickXpath = clickEvent.getXpath();

        ClickNode clickNode = getClickNode(clickXpath, clickText);

        if(clickNode == null){ //If no click node could be found
            //Create the click node
            ClickNode newClickNode = new ClickNode();
            newClickNode.setId(UUID.randomUUID());
            newClickNode.setText(clickText);
            newClickNode.setXpath(clickXpath);
            newClickNode.setInstances(Set.of(entityTimelineId));
            clickNode = newClickNode;
        }else{
            //If a click node was found, update its list of instances
            clickNode.getInstances().add(entityTimelineId);
        }

        final ClickNode finalClickNode = clickNode;

        //Write the changes to the click node back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", finalClickNode.getXpath());
            props.put("text", finalClickNode.getText());
            props.put("id", finalClickNode.getId().toString());
            props.put("instances", finalClickNode.getInstances());

            session.executeWrite(tx->{
                var stmt = "MERGE (n:ClickNode { xpath: $xpath, text: $text} ) ON CREATE SET n =  $props ON MATCH SET n = $props RETURN n;";
                var query = new Query(stmt, parameters("xpath", finalClickNode.getXpath(), "text", finalClickNode.getText(), "props", props));
                tx.run(query);

                return 0;
            });
        }
    }

    public void processEffect(Timeline timeline, Effect effect){
        var index = timeline.indexOf(effect);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        EffectNode effectNode = null;
        boolean withSucessor = false;
        boolean withBoth = false;
        NavNode anchor = null;
        NavNode anchor2 = null;
        if(index == 0){
            anchor = resolveNavNode(timeline, effect, 1);
            effectNode = getEffectNodeBySuccessor(anchor);
            withSucessor = true;
        }else if(index == timeline.size()-1){
            anchor = resolveNavNode(timeline, effect, -1);
            effectNode = getEffectNodeByPredecessor(anchor);
        }else{
            withBoth = true;
            anchor = resolveNavNode(timeline, effect, -1);
            anchor2 = resolveNavNode(timeline, effect, 1);
            effectNode = getEffectNodeByNeighbours(anchor, anchor2);
        }

        if(effectNode == null){
            //If no effect node was found, create one.
            EffectNode newEffectNode = new EffectNode();
            newEffectNode.setId(UUID.randomUUID());
            newEffectNode.setInstances(Set.of(entityTimelineId));
            effectNode = newEffectNode;
        }else{
            //If an effect node was found update its instances
            effectNode.getInstances().add(entityTimelineId);
        }

        final EffectNode finalEffectNode = effectNode;
        final boolean finalWithSucessor = withSucessor;
        final boolean finalWithBoth = withBoth;
        final NavNode finalAnchor = anchor;
        final NavNode finalAnchor2 = anchor2;

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            HashMap<String, Object> props = new HashMap<>();
            props.put("id", finalEffectNode.getId().toString());
            props.put("instances", finalEffectNode.getInstances());

            log.info("for {}[{}]", effect.symbol(), index);
            session.executeWrite(tx->{
                var stmt = "";
                Query query = null;
                if(finalWithBoth){
                    stmt = "MATCH (a {id:$aId}), (b {id:$bId}) MERGE (a)-[:NEXT]->(e:EffectNode)-[:NEXT]->(b) ON CREATE SET e = $props ON MATCH SET e = $props RETURN e;";
                    query = new Query(stmt, parameters("aId", finalAnchor.getId().toString(), "bId", finalAnchor2.getId().toString(), "props", props));
                }else{
                    if(finalWithSucessor){
                        stmt = "MATCH (a {id:$id}) MERGE (e:EffectNode)-[:NEXT]->(a) ON CREATE SET e = $props ON MATCH SET e = $props RETURN e;";
                    }else{
                        stmt = "MATCH (a {id:$id}) MERGE (a)-[:NEXT]->(e:EffectNode) ON CREATE SET e = $props ON MATCH SET e = $props RETURN e;";
                    }
                    query = new Query(stmt, parameters("id", finalAnchor.getId().toString(), "props", props));
                }


                var result = tx.run(query);
                log.info("Created EffectNode:");
                result.list().stream().forEach(r->log.info("{}",r.get(0).asNode().get("id").asString()));
                return 0;

            });

        }

        effectMap.put(effect, finalEffectNode.getId());

    }

    public NavNode resolveNavNode(Timeline timeline, int index){
        log.info("Attempting to resolve {}[{}]", timeline.get(index).symbol(), index);

        TimelineEntity target = timeline.get(index);
        if (target instanceof ClickEvent){
            ClickEvent clickEvent = (ClickEvent)target;
            return getClickNode(clickEvent.getXpath(), clickEvent.getTriggerElement() != null ? clickEvent.getTriggerElement().ownText():"");
        }

        if(target instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) target;
            return getDataEntryNode(dataEntry.xpath());
        }

        if(target instanceof NetworkEvent){
            NetworkEvent networkEvent = (NetworkEvent) target;
            return getAPINode(networkEvent.getPath(), networkEvent.getMethod());
        }

        if(target instanceof Effect){
            Effect effect = (Effect) target;
            if(!effectMap.containsKey(effect)){
                throw new RuntimeException("Tried to resolve an effect that was not created!");
            }
            return getEffectNodeById(effectMap.get(effect).toString());
        }

        if(target instanceof ApplicationLocationChange){
            ApplicationLocationChange applicationLocationChange = (ApplicationLocationChange) target;
            LocationNode node = getLocationNode(applicationLocationChange.getToPath());
            if(node == null){
                throw new RuntimeException("ApplicationLocationChange failed to resolve to location node!");
            }
            return node;
            //return getApplicationLocationChangeNode(applicationLocationChange.getFromPath(), applicationLocationChange.getToPath());
        }

        log.warn("Unknown entity type");
        return null;
    }

    private NavNode resolveNavNode(Timeline timeline, TimelineEntity entity, int indexOffset){
        var index = timeline.indexOf(entity);
        return resolveNavNode(timeline, index+indexOffset);
    }

    private EffectNode getEffectNodeById(String id){
        log.info("Resolving effect using id: {}", id);
        var stmt = "MATCH (e:EffectNode {id:$id}) RETURN e;";
        var query = new Query(stmt, parameters("id", id));

        return readNode(query, EffectNode.class);
    }

    private EffectNode getEffectNodeByNeighbours(NavNode predecessor, NavNode successor){

        if(predecessor instanceof EffectNode || successor instanceof EffectNode){
            log.error("Effect nodes cannot be neighbours of other effect nodes!");
            throw new RuntimeException("Effect nodes cannot be neighbours of other effect nodes!");
        }

        var stmt = "";
        if(predecessor instanceof ClickNode){
            stmt = "MATCH (a:ClickNode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }
        if(predecessor instanceof DataEntryNode){
            stmt = "MATCH (a:DataEntryNode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }

        if(predecessor instanceof APINode){
            stmt = "MATCH (a:APINode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }

        if(predecessor instanceof ApplicationLocationChangeNode){
            stmt = "MATCH (a:ApplicationLocationChangeNode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }

        if(predecessor instanceof LocationNode){
            stmt = "MATCH (a:LocationNode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }

        if(successor instanceof ClickNode){
            stmt += "-[:NEXT]->(b:ClickNode {id:$bId}) RETURN e;";
        }

        if(successor instanceof DataEntryNode){
            stmt+="-[:NEXT]->(b:DataEntryNode {id:$bId}) RETURN e;";
        }

        if(successor instanceof APINode){
            stmt += "-[:NEXT]->(b:APINode {id:$bId}) RETURN e;";
        }

        if(successor instanceof LocationNode){
            stmt += "-[:NEXT]->(b:LocationNode {id:$bId}) RETURN e;";
        }

        if(successor instanceof ApplicationLocationChangeNode){
            stmt +="-[:NEXT]->(b:ApplicationLocationChangeNode {id:$bId}) RETURN e;";
        }

        Query query = new Query(stmt, parameters("aId", predecessor.getId().toString(), "bId", successor.getId().toString()));

        EffectNode effectNode = readNode(query, EffectNode.class);

        return effectNode;

    }

    private EffectNode getEffectNodeBySuccessor(NavNode successor){
        var stmt = "";
        if(successor instanceof ClickNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:ClickNode {id:$id}) RETURN e;";
        }

        if(successor instanceof DataEntryNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:DataEntryNode {id:$id}) RETURN e;";
        }

        if(successor instanceof APINode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:APINode {id:$id}) RETURN e;";
        }

        if(successor instanceof ApplicationLocationChangeNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:ApplicationLocationChangeNode {id:$id}) RETURN e;";
        }

        if(successor instanceof LocationNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:LocationNode {id:$id}) RETURN e;";
        }

        Query query = new Query(stmt, parameters("id", successor.getId().toString()));

        EffectNode effectNode = readNode(query, EffectNode.class);

        return effectNode;
    }

    private EffectNode getEffectNodeByPredecessor(NavNode predecessor){

        var stmt = "";
        if(predecessor instanceof ClickNode){
            stmt = "MATCH (n:ClickNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        if(predecessor instanceof DataEntryNode){
            stmt = "MATCH (n:DataEntryNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        if(predecessor instanceof APINode){
            stmt = "MATCH (n:APINode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        if(predecessor instanceof ApplicationLocationChangeNode){
            stmt = "MATCH (n:ApplicationLocationChangeNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        if(predecessor instanceof LocationNode){
            stmt = "MATCH (n:LocationNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        Query query = new Query(stmt, parameters("id", predecessor.getId().toString()));

        EffectNode effectNode = readNode(query, EffectNode.class);

        return effectNode;

    }

    public void bind(NavNode a, NavNode b){
        var stmt = "MATCH (a {id:$aId}), (b {id:$bId}) MERGE (a)-[:NEXT]->(b);";
        var query = new Query(stmt, parameters("aId", a.getId().toString(), "bId", b.getId().toString()));
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            session.executeWrite(tx->{
                tx.run(query);
                return 0;
            });
        }
    }


    private APINode getAPINode(String path, String method){
        var stmt = "MATCH (n:APINode {path:$path, method:$method}) return n;";
        var query = new Query(stmt, parameters("path", path, "method", method));

        return readNode(query, APINode.class);
    }

    private ApplicationLocationChangeNode getApplicationLocationChangeNode(String from, String to){
        var stmt = "MATCH (n:ApplicationLocationChangeNode {from:$from, to:$to}) return n";
        var query = new Query(stmt, parameters("from", from, "to", to));

        return readNode(query, ApplicationLocationChangeNode.class);
    }

    private DataEntryNode getDataEntryNode(String xpath){
        var stmt = "MATCH (n:DataEntryNode {xpath:$xpath}) return n";
        var query = new Query(stmt, parameters("xpath", xpath));

        return readNode(query, DataEntryNode.class);
    }

    private ClickNode getClickNode(String xpath, String text){

        var stmt = "MATCH (n:ClickNode {xpath:$xpath, text:$text}) return n;";
        var query = new Query(stmt, parameters("xpath", xpath, "text", text));

        return readNode(query, ClickNode.class);

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


    public DataEntryNode readDataEntryNode(Query query){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            Record node = session.executeRead(tx->{
                var result = tx.run(query);
                try{
                    return result.single();
                }catch (NoSuchRecordException err){
                    log.info("Data Entry node could not be found.");
                    return null;
                }
            });

            if(node != null){
                return DataEntryNode.fromRecord(node);
            }else{
                return null;
            }
        }
    }

    public ClickNode readClickNode(Query query){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
             Record node = session.executeRead(tx -> {

                var result = tx.run(query);

                try{
                    return result.single();
                }catch(NoSuchRecordException err) {
                    log.info("Click node could not be found.");
                    return null;
                }
            });

            if(node != null){
                return ClickNode.fromRecord(node);
            }else{
                return null;
            }
        }
    }

    public <T extends NavNode> T readNode(Query query, Class<T> tClass){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            Record node = session.executeRead(tx->{
                var result = tx.run(query);
                try{
                    return result.single();
                }catch (NoSuchRecordException err){
                    log.info("Could not find {} node. ", tClass.getName());
                    return null;
                }
            });
            try{
                if(node != null){
                    return (T) tClass.getMethod("fromRecord", Record.class).invoke(null, node);
                }else{
                    return null;
                }
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

        }
    }


    public void write(Query query){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            session.executeWrite(tx->{
                tx.run(query);
                return 0;
            });
        }
    }
}
