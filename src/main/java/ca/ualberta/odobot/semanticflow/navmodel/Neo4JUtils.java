package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.mind2web.*;
import ca.ualberta.odobot.semanticflow.model.*;

import ca.ualberta.odobot.semanticflow.navmodel.nodes.*;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.exceptions.NoSuchRecordException;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.neo4j.driver.Values.parameters;

public class Neo4JUtils {

    private static final Logger log = LoggerFactory.getLogger(Neo4JUtils.class);
    private final Driver driver;

    private static final String databaseName = "neo4j";

    public HashMap<Effect, UUID> effectMap = new HashMap<>();

    public Neo4JUtils(String uri, String user, String password){
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user,password));
    }


    /**
     * A wrapper method for {@link #processLocation(String, String)}
     * @param path
     * @param timeline
     * @return
     */
    public LocationNode getOrCreateLocation(String path, Timeline timeline){

        return processLocation(timeline.getId().toString(), path);
    }



    public LocationNode getLocationNode(String path){
        var stmt = "MATCH (n:LocationNode {path:$path}) return n;";
        var query = new Query(stmt, parameters("path", path));

        return readNode(query, LocationNode.class);
    }


    /**
     * A wrapper method for {@link #processLocation(String, String)} to simplify processing {@link ApplicationLocationChange}s
     * @param timeline
     * @param applicationLocationChange
     */
    public void processApplicationLocationChange(Timeline timeline, ApplicationLocationChange applicationLocationChange){
        var index = timeline.indexOf(applicationLocationChange);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        //TODO - Unsure which of the two invokations below is best.
        processLocation(entityTimelineId, applicationLocationChange.getToPath());
        //processLocation(timeline.getId().toString(), applicationLocationChange.getToPath());

    }


    /**
     * A wrapper method for {@link #processDataEntry(String, String)} to simplify processing {@link Type}s.
     * @param type
     */
    public void processType(Type type){
        processDataEntry(type.getTargetElementXpath(), type.getActionId());
    }

    public void processType(Type type, String website){
        processDataEntry(type.getTargetElementXpath(), type.getActionId(), website);
    }

    /**
     * A wrapper method for {@link #processDataEntry(String, String)} to simplify processing {@link DataEntry}s.
     * @param timeline
     * @param dataEntry
     */
    public void processDataEntry(Timeline timeline, DataEntry dataEntry){
        var index = timeline.indexOf(dataEntry);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        processDataEntry(dataEntry.xpath(), entityTimelineId);
    }

    /**
     * A wrapper method for {@link #processNetworkEvent(String, String, String)} to simplify processing {@link NetworkEvent}s.
     * @param timeline
     * @param networkEvent
     */
    public void processNetworkEvent(Timeline timeline, NetworkEvent networkEvent){
        var index = timeline.indexOf(networkEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        processNetworkEvent(entityTimelineId, networkEvent.getPath(), networkEvent.getMethod());
    }

    public LocationNode processLocation(String eventId, String path){

        //If a location node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<LocationNode> existingLocationNodeSupplier = ()->getLocationNode(path);

        //If no location node could be found, this supplier will be used to create one
        Supplier<LocationNode> newLocationNodeSupplier = ()->{
            LocationNode node = new LocationNode();
            node.setId(UUID.randomUUID());
            node.setPath(path);
            node.setInstances(Set.of(eventId));
            return node;
        };

        //The update query used to update/merge the processed location node into the database.
        Function<LocationNode,Query> queryFunction = (locationNode)->{
            HashMap<String,Object> props = new HashMap<>();
            props.put("path", locationNode.getPath());
            props.put("id", locationNode.getId().toString());
            props.put("instances", locationNode.getInstances());

            var stmt = "MERGE (n:LocationNode {path:$path}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            var query = new Query(stmt, parameters("path", locationNode.getPath(), "props", props));

            return query;
        };

        //Invoke generic processing logic
        return processNode(eventId, LocationNode.class, existingLocationNodeSupplier, newLocationNodeSupplier, queryFunction);
    }

    /**
     * A wrapper method for {@link #processSelectOption(String, String)} to simplify processing {@link SelectOption}
     * @param selectOption
     */
    public void processSelectOption(SelectOption selectOption){
        processSelectOption(selectOption.getTargetElementXpath(), selectOption.getActionId());
    }

    public void processSelectOption(SelectOption selectOption, String website){
        processSelectOption(selectOption.getTargetElementXpath(), selectOption.getActionId(), website);
    }

    public void processSelectOption(String xpath, String eventId, String website){
        //If a select option node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<SelectOptionNode> existingSelectOptionNodeSupplier = ()->getSelectOptionNode(xpath, website);

        //Invoke generic processing logic
        processNode(
                eventId,
                SelectOptionNode.class,
                existingSelectOptionNodeSupplier,
                newSelectOptionNodeSupplier(xpath, eventId, website),
                processSelectOptionNodeQueryFunction()
        );

    }

    public void processEnd(String website, String eventId){
        //If a start node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<EndNode> existingEndNodeSupplier = ()->getEndNode(website);

        //Invoke generic processing logic.
        processNode(
                eventId,
                EndNode.class,
                existingEndNodeSupplier,
                ()->{
                    EndNode node = new EndNode();
                    node.setId(UUID.randomUUID());
                    node.setWebsite(website);
                    node.setInstances(Set.of(eventId));
                    return node;},
                processEndNodeQueryFunction()
        );
    }

    public void processStart(String website, String eventId){
        //If a start node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<StartNode> existingStartNodeSupplier = ()->getStartNode(website);

        //Invoke generic processing logic.
        processNode(
                eventId,
                StartNode.class,
                existingStartNodeSupplier,
                ()->{
                    StartNode node = new StartNode();
                    node.setId(UUID.randomUUID());
                    node.setWebsite(website);
                    node.setInstances(Set.of(eventId));
                    return node;},
                processStartNodeQueryFunction()
        );
    }

    public void processSelectOption(String xpath, String eventId){

        //If a select option node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<SelectOptionNode> existingSelectOptionNodeSupplier = ()->getSelectOptionNode(xpath);


        //Invoke generic processing logic
        processNode(
                eventId,
                SelectOptionNode.class,
                existingSelectOptionNodeSupplier, //If a select option node for this event already exists in the database, this supplier will be used to retrieve it.
                newSelectOptionNodeSupplier(xpath, eventId), //If no select option node could be found, this supplier will be used to create one
                processSelectOptionNodeQueryFunction()
        );

    }

    private Supplier<SelectOptionNode> newSelectOptionNodeSupplier(String xpath, String eventId, String website){
        return ()->{
            SelectOptionNode node = this.newSelectOptionNodeSupplier(xpath, eventId).get();
            node.setWebsite(website);
            return node;
        };
    }

    private Supplier<SelectOptionNode> newSelectOptionNodeSupplier(String xpath, String eventId){
        Supplier<SelectOptionNode> newSelectOptionNodeSupplier = ()->{
            SelectOptionNode node = new SelectOptionNode();
            node.setId(UUID.randomUUID());
            node.setXpath(xpath);
            node.setInstances(Set.of(eventId));
            return node;
        };
        return newSelectOptionNodeSupplier;
    }

    private Function<EndNode, Query> processEndNodeQueryFunction(){
        //The update query used to update/merge the processed start node into the database.
        Function<EndNode, Query> queryFunction = (endNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("id", endNode.getId().toString());
            props.put("instances", endNode.getInstances());

            return makeGenericMergeQuery("EndNode", endNode, props, "props", props);
        };

        return queryFunction;
    }


    private Function<StartNode, Query> processStartNodeQueryFunction(){
        //The update query used to update/merge the processed start node into the database.
        Function<StartNode, Query> queryFunction = (startNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("id", startNode.getId().toString());
            props.put("instances", startNode.getInstances());

            return makeGenericMergeQuery("StartNode", startNode, props, "props", props);
        };

        return queryFunction;
    }

    private Function<SelectOptionNode, Query> processSelectOptionNodeQueryFunction(){
        //The update query used to update/merge the processed select option node into the database.
        Function<SelectOptionNode, Query> queryFunction = (selectOptionNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", selectOptionNode.getXpath());
            props.put("id", selectOptionNode.getId().toString());
            props.put("instances", selectOptionNode.getInstances());

            return makeGenericMergeQuery("SelectOptionNode", selectOptionNode, props, "xpath", selectOptionNode.getXpath(), "props", props);
        };

        return queryFunction;
    }

    public void processNetworkEvent(String eventId, String networkEventPath, String networkEventMethod){

        //If an API node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<APINode> existingAPINodeSupplier = ()->getAPINode(networkEventPath, networkEventMethod);

        //If no API node could be found, this supplier will be used to create one
        Supplier<APINode> newAPINodeSupplier = ()->{
            APINode node = new APINode();
            node.setPath(networkEventPath);
            node.setMethod(networkEventMethod);
            node.setId(UUID.randomUUID());
            node.setInstances(Set.of(eventId));
            return node;
        };

        //The update query used to update/merge the processed API node into the database.
        Function<APINode, Query> queryFunction = (apiNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("path", apiNode.getPath());
            props.put("method", apiNode.getMethod());
            props.put("id", apiNode.getId().toString());
            props.put("instances", apiNode.getInstances());

            var stmt = "MERGE (n:APINode { path:$path, method:$method }) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            var query = new Query(stmt, parameters("path", apiNode.getPath(), "method", apiNode.getMethod() , "props", props));

            return query;
        };

        //Invoke generic processing logic.
        processNode(
                eventId,
                APINode.class,
                existingAPINodeSupplier,
                newAPINodeSupplier,
                queryFunction);
    }

    public void processDataEntry(String xpath, String eventId, String website){
        //If a data entry node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<DataEntryNode> existingDataEntryNodeSupplier = ()->getDataEntryNode(xpath, website);

        //Invoke generic processing logic.
        processNode(
                eventId,
                DataEntryNode.class,
                existingDataEntryNodeSupplier,
                newDataEntryNodeSupplier(xpath, eventId, website),
                processDataEntryNodeQueryFunction()
        );
    }

    public void processDataEntry(String xpath, String eventId){

        //If a data entry node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<DataEntryNode> existingDataEntryNodeSupplier = ()->getDataEntryNode(xpath);

        //Invoke generic processing logic.
        processNode(
                eventId,
                DataEntryNode.class,
                existingDataEntryNodeSupplier, //If a data entry node for this event already exists in the database, this supplier will be used to retrieve it.
                newDataEntryNodeSupplier(xpath, eventId), //If no data entry node could be found, this supplier will be used to create one
                processDataEntryNodeQueryFunction()//The update query used to update/merge the processed data entry node into the database.
        );

    }

    private Function<DataEntryNode, Query> processDataEntryNodeQueryFunction(){

        Function<DataEntryNode, Query> queryFunction = (dataEntryNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", dataEntryNode.getXpath());
            props.put("id", dataEntryNode.getId().toString());
            props.put("instances", dataEntryNode.getInstances());

            return makeGenericMergeQuery("DataEntryNode", dataEntryNode, props, "xpath", dataEntryNode.getXpath(), "props", props);
        };

        return queryFunction;
    }

    private Supplier<DataEntryNode> newDataEntryNodeSupplier (String xpath, String eventId, String website){
        Supplier<DataEntryNode> newDataEntryNodeSupplier = ()->{
            DataEntryNode node = this.newDataEntryNodeSupplier(xpath, eventId).get();
            node.setWebsite(website);
            return node;
        };
        return newDataEntryNodeSupplier;
    }

    private Supplier<DataEntryNode> newDataEntryNodeSupplier (String xpath, String eventId){
        //If no data entry node could be found, this supplier will be used to create one
        Supplier<DataEntryNode> newDataEntryNodeSupplier = ()->{
            DataEntryNode node = new DataEntryNode();
            node.setId(UUID.randomUUID());
            node.setXpath(xpath);
            node.setInstances(Set.of(eventId));
            return node;
        };
        return newDataEntryNodeSupplier;
    }

    public void processClick(String clickText, String clickXpath, String eventId, String website){
        //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<ClickNode> existingClickNodeSupplier = ()->getClickNode(clickXpath, clickText, website);

        //Invoke generic processing logic.
        processNode(
                eventId,
                ClickNode.class,
                existingClickNodeSupplier, //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
                newClickNodeSupplier(clickText, clickXpath, eventId, website), //If no click node could be found, this supplier will be used to create one
                processClickQueryFunction()
        );

    }

    public void processClick(String clickText, String clickXpath, String eventId){

        //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<ClickNode> existingClickNodeSupplier = ()->getClickNode(clickXpath, clickText);

        //Invoke generic processing logic.
        processNode(
                eventId,
                ClickNode.class,
                existingClickNodeSupplier, //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
                newClickNodeSupplier(clickText, clickXpath, eventId), //If no click node could be found, this supplier will be used to create one
                processClickQueryFunction()
        );
    }

    /**
     * Supplies new click nodes
     * @param clickText
     * @param clickXpath
     * @param eventId
     * @param website
     * @return
     */
    private Supplier<ClickNode> newClickNodeSupplier(String clickText, String clickXpath, String eventId, String website){
        Supplier<ClickNode> newClickNodeSupplier = ()->{
            ClickNode clickNode = this.newClickNodeSupplier(clickText, clickXpath, eventId).get();
            clickNode.setWebsite(website);
            return clickNode;
        };
        return newClickNodeSupplier;
    }

    /**
     *  Supplies new click nodes
     * @return
     */
    private Supplier<ClickNode> newClickNodeSupplier(String clickText, String clickXpath, String eventId){
        Supplier<ClickNode> newClickNodeSupplier = ()->{
            ClickNode clickNode = new ClickNode();
            clickNode.setId(UUID.randomUUID());
            clickNode.setText(clickText);
            clickNode.setXpath(clickXpath);
            clickNode.setInstances(Set.of(eventId));
            return clickNode;
        };
        return newClickNodeSupplier;
    }

    /**
     * @return A cypher query for matching or creating a ClickNode.
     */
    private Function<ClickNode, Query> processClickQueryFunction(){
        //The update query used to update/merge the processed click node into the database.
        Function<ClickNode, Query> queryFunction = (clickNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", clickNode.getXpath());
            props.put("text", clickNode.getText());
            props.put("id", clickNode.getId().toString());
            props.put("instances", clickNode.getInstances());

            return makeGenericMergeQuery("ClickNode", clickNode, props, "xpath", clickNode.getXpath(), "text", clickNode.getText(), "props", props);

        };
        return queryFunction;
    }


    /**
     * Helper method that returns a cypher query to match or create a specific node based on its propery values.
     * @param nodeLabel
     * @param node
     * @param props the properties of the node to create/update.
     * @param keysAndValues the properties used to match the node + props.
     * @return
     * @param <T>
     */
    private  <T extends NavNode> Query makeGenericMergeQuery(String nodeLabel, T node, HashMap<String,Object> props, Object... keysAndValues){

        Map<String,Object> map = keysAndValuesToMap(keysAndValues);

        String stmt = null;
        Query query = null;
        //If we're working with mind2web data, click nodes will have an additional website property we need to match.
        if(node.getWebsite() != null){
            props.put("website", node.getWebsite()); //Add website to the list of properties the node should have.
            map.put("website", node.getWebsite()); //Add website to the list of properties used to match the node.
            String[] stringProps = map.keySet().stream()
                    .filter(k->!k.equals("props")) //'props' should not be a property value, it is passed so we can use it in the parameters() call, and it represents node properties as a whole, but is not in fact a property 'props' with corresponding map value.
                    .toArray(String[]::new); //Convert matching properties to a string array for use with makeSimpleNodeQuerySegment
            stmt = "MERGE "+ makeSimpleNodeQuerySegment("n", nodeLabel, stringProps) + " ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            query = new Query(stmt, parameters(mapToKeysAndValues(map)));
        }else{
            String [] stringProps = map.keySet().stream()
                    .filter(k->!k.equals("props")) //'props' should not be a property value, it is passed so we can use it in the parameters() call.
                    .toArray(String[]::new); //Convert matching properties to a string array for use with makeSimpleNodeQuerySegment
            stmt = "MERGE "+ makeSimpleNodeQuerySegment("n", nodeLabel, stringProps) + " ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            query = new Query(stmt, parameters(keysAndValues));
        }

        //log.info("Merge Query:\n{}", stmt); //For debugging

        return query;

    }

    private static Object[] mapToKeysAndValues(Map<String,Object> map){

        List<Object> objects = new ArrayList<>();

        Iterator<Map.Entry<String,Object>> it = map.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String,Object> entry = it.next();
            objects.add(entry.getKey());
            objects.add(entry.getValue());
        }

        return objects.toArray();
    }

    private static Map<String,Object> keysAndValuesToMap(Object... keysAndValues){
        Map<String, Object> map = new HashMap<>();
        String key = null;
        Object value = null;
        for(int i = 0; i < keysAndValues.length; i++){
            if(i % 2 == 0){ //If i is even it is an index to a key.
                key = (String) keysAndValues[i];
            }else{ //Otherwise i is odd and is an index to a value.
                value = keysAndValues[i];
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Generic method for processing simple {@link NavNode}s.
     *
     * First the method checks if the nav node already exists in the database. If so, it updates the existing
     * nav node's instances. Otherwise, it creates a new nav node.
     *
     * The new or updated nav node is then updated/merged in the database.
     *
     * @param eventId the UUID of the event for which a nav node is being created or updated.
     * @param nodeClass the class of NavNode being processed.
     * @param existingNodeSupplier A supplier that returns the corresponding nav node if it already exists in the database.
     * @param newNodeSupplier A supplier that create a new nav node, invoked if the nav node does not already exist in the database.
     * @param queryFunction A function that returns the update query for the nav node being processed.
     * @param <T> The type of nav node being processed.
     * @return The processed nav node.
     */
    private <T extends NavNode> T processNode(String eventId,
                             Class<T> nodeClass,
                             Supplier<T> existingNodeSupplier,
                             Supplier<T> newNodeSupplier,
                             Function<T,Query> queryFunction){

        log.info("Processing {} event", nodeClass.getName());

        T node = existingNodeSupplier.get();

        if(node == null){ //Node cannot be found
            //Create it
            node = newNodeSupplier.get();
        }else{
            //If the node was found, update its list of instances.
            node.getInstances().add(eventId);
        }

        final T finalNode = node;

        //Write changes to the node back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            session.executeWrite(tx->{
                tx.run(queryFunction.apply(finalNode));
                return 0;
            });
        }

        return node;
    }

    public void processClick(Click click){
        String clickText = click.targetElement().ownText();
        String xpath = click.getTargetElementXpath();

        processClick(clickText, xpath, click.getActionId());
    }

    public void processClick(Click click, String website){
        processClick(click.targetElement().ownText(), click.getTargetElementXpath(), click.getActionId(), website);
    }

    public void processClickEvent(Timeline timeline, ClickEvent clickEvent){
        var index  = timeline.indexOf(clickEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        String clickText = clickEvent.getTriggerElement() != null?clickEvent.getTriggerElement().ownText():"";
        String clickXpath = clickEvent.getXpath();

        processClick(clickText, clickXpath, entityTimelineId);
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

    public NavNode resolveNavNode(Operation operation, String website){

        if(operation instanceof Click){
            Click click = (Click) operation;
            return getClickNode(click.getTargetElementXpath(), click.targetElement().ownText(), website);
        }

        if(operation instanceof Type){
            Type type = (Type) operation;
            return getDataEntryNode(type.getTargetElementXpath(), website);
        }

        if(operation instanceof SelectOption){
            SelectOption selectOption = (SelectOption) operation;
            return getSelectOptionNode(selectOption.getTargetElementXpath(), website);
        }

        if(operation instanceof Start){
            return getStartNode(website);
        }

        if(operation instanceof End){
            return getEndNode(website);
        }

        log.warn("Unrecognized Mind2Web event type!");
        return null;

    }

    public NavNode resolveNavNode(Operation operation){

        if(operation instanceof Click){
            Click click = (Click) operation;
            return getClickNode(click.getTargetElementXpath(), click.targetElement().ownText());
        }

        if(operation instanceof Type){
            Type type = (Type) operation;
            return getDataEntryNode(type.getTargetElementXpath());
        }

        if(operation instanceof SelectOption){
            SelectOption selectOption = (SelectOption) operation;
            return getSelectOptionNode(selectOption.getTargetElementXpath());
        }

        log.warn("Unrecognized Mind2Web event type!");
        return null;

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

    private APINode getAPINode(String path, String method, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("APINode", "path", "method", "website");
        var query = new Query(stmt, parameters("path", path, "method", method, "website", website));
        return readNode(query, APINode.class);
    }

    private EndNode getEndNode(String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("EndNode", "website");
        var query = new Query(stmt, parameters("website", website));
        return readNode(query, EndNode.class);
    }

    private StartNode getStartNode(String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("StartNode", "website");
        var query = new Query(stmt, parameters("website", website));
        return readNode(query, StartNode.class);
    }

    private SelectOptionNode getSelectOptionNode(String xpath){
        var stmt = "MATCH (n:SelectOptionNode {xpath:$xpath}) return n";
        var query = new Query(stmt, parameters("xpath", xpath));

        return readNode(query, SelectOptionNode.class);
    }

    private SelectOptionNode getSelectOptionNode(String xpath, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("SelectOptionNode", "xpath", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "website", website));

        log.info("getSelectOptionNode Query: {}", stmt);

        return readNode(query, SelectOptionNode.class);
    }

    private DataEntryNode getDataEntryNode(String xpath){
        var stmt = "MATCH (n:DataEntryNode {xpath:$xpath}) return n";
        var query = new Query(stmt, parameters("xpath", xpath));
        return readNode(query, DataEntryNode.class);
    }

    private DataEntryNode getDataEntryNode(String xpath, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("DataEntryNode", "xpath", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "website", website));
        return readNode(query, DataEntryNode.class);
    }

    private ClickNode getClickNode(String xpath, String text){
        var stmt = "MATCH (n:ClickNode {xpath:$xpath, text:$text}) return n;";
        var query = new Query(stmt, parameters("xpath", xpath, "text", text));
        return readNode(query, ClickNode.class);

    }

    private ClickNode getClickNode(String xpath, String text, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("ClickNode", "xpath", "text", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "text", text, "website", website));
        //log.info("getClickNode Query:\n{}\n{}", stmt, stmt.replaceAll("\\$xpath", "\""+xpath+"\"").replaceAll("\\$text", "\""+text+"\"").replaceAll("\\$website", "\""+website+"\""));
        return readNode(query, ClickNode.class);
    }

    /**
     * Helper method for constructing cypher query that matches nodes via simple property match.
     *
     * For example: passing in (ClickNode, xpath, text) will produce "MATCH (n:ClickNode {xpath:$xpath, text:$text}) return n;"
     *
     * @param nodeLabel the label of the node being matched
     * @param props the properties to include
     * @return the query string
     */
    private static String makeSimplePropertyBasedMatchQueryString(String nodeLabel, String... props ){
        var stmt = "MATCH "+ makeSimpleNodeQuerySegment("n", nodeLabel, props)+" return n;";
        return stmt;
    }

    /**
     * Helper method for constructing cypher queries.
     *
     * For example: passing in (n, ClickNode, xpath, text) will produce: "(n:ClickNode {xpath:$xpath, text:$text})"
     * @param nodeVariable
     * @param nodeLabel
     * @param props
     * @return
     */
    private static String makeSimpleNodeQuerySegment(String nodeVariable, String nodeLabel, String... props){
        var stmt = "(" + nodeVariable + ":" + nodeLabel + " " + makeNodePropertyQuerySegment(props) + ")";
        return stmt;
    }

    /**
     * Helper method for constructing cypher query node property segments.
     *
     * For example: passing in "xpath, text" will produce "{xpath:$xpath, text:$text}".
     *
     * @param props the properties to create the segment for.
     * @return
     */
    private static String makeNodePropertyQuerySegment(String... props){
        String result = "{";
        Iterator<String> it = Arrays.stream(props).iterator();
        while (it.hasNext()){
            String property = it.next();
            result += property + ":$" + property;
            if(it.hasNext()){
                result += ",";
            }
        }
        result += "}";

        return result;
    }

    /**
     * For a collection of annotation_ids (in the mind2web dataset), returns all corresponding start nodes.
     * @param ids
     */
    public List<StartNode> getStartNodes(List<String> ids){
        log.info("annotationIds: {}", ids);
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String sQuery = """
                match (s:StartNode) where ANY (id in s.instances WHERE id in $annotation_ids) return s;
                """;
            Query query = new Query(sQuery, parameters("annotation_ids", ids));

            List<Record> results =session.executeRead(tx->{
                var result = tx.run(query);

                return result.list();
            });

            List<StartNode> startNodes = results.stream()
                    .map(record->StartNode.fromRecord(record))
                    .collect(Collectors.toList());

            return startNodes;
        }
    }

    /**
     * For a collection of annotation_ids (in the mind2web dataset), returns all corresponding start nodes.
     * @param ids
     */
    public List<EndNode> getEndNodes(List<String> ids){

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String sQuery = """
                match (s:EndNode) where ANY (id in s.instances WHERE id in $annotation_ids) return s;
                """;
            Query query = new Query(sQuery, parameters("annotation_ids", ids));

            List<Record> results =session.executeRead(tx->{
                var result = tx.run(query);

                return result.list();
            });

            List<EndNode> endNodes = results.stream()
                    .map(record->EndNode.fromRecord(record))
                    .collect(Collectors.toList());

            return endNodes;
        }
    }

    public List<NavPath> getMind2WebPaths(Transaction tx, List<StartNode> startNodes, List<EndNode> endNodes){

        if(startNodes.size() != endNodes.size()){
            log.error("Expecting the same number of start ({}) and end ({}) nodes!", startNodes.size(),endNodes.size());
            return null;
        }

        List<NavPath> paths = startNodes.stream()
                .map(startNode->{

                    EndNode correspondingEndNode = endNodes.stream().filter(endNode->endNode.getInstances().containsAll(startNode.getInstances())).findFirst().get();

                    return LogPreprocessor.pathsConstructor.constructMind2Web(tx, startNode.getId(), correspondingEndNode.getId());


                })
                .collect(ArrayList::new, (allPaths, p)->allPaths.addAll(p), ArrayList::addAll);

        return paths;

    }

    public <T extends NavNode> T readNode(Query query, Class<T> tClass){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            Record node = session.executeRead(tx->{
                var result = tx.run(query);
                try{
                    return result.single();
                }catch (NoSuchRecordException err){
                    //log.info("Could not find {} node. ", tClass.getName());
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

    public void createNodeLabelsUsingWebsiteProperty(){

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            session.executeWriteWithoutResult(txContext->{

                /**
                 * This cypher query begins by getting all values for the website property from all nodes.
                 * It then collects those values and removes any duplicates, resulting in a list of unique website property values.
                 * Then, for each unique website property value, it matches all nodes whose website property has that value
                 * and adds a label with that value to the node.
                 */
                var stmt = """
                        match (n) with collect(n.website) as websites 
                        unwind websites as website with distinct website with collect(website) as unique_sites 
                        unwind unique_sites as site 
                        match(n) where n.website = site 
                        call apoc.create.addLabels(n,[site]) yield node 
                        return node
                        """;

                txContext.run(stmt);

            });
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


    public List<String> getAllXpaths(){
        String sQuery = """
                    match (n) where n.xpath is not null return n.xpath;
                    """;
        Query query = new Query(sQuery);
        return getXpaths(query);
    }

    public List<String> getXpathsForWebsite(String website){
        String sQuery = """
                match (n) where n.xpath is not null and n.website = $website return n.xpath;
                """;
        Query  query = new Query(sQuery, parameters("website", website));

        return getXpaths(query);
    }

    /**
     * Returns xpaths in the graph model.
     * @return
     */
    private List<String> getXpaths(Query query){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            List<Record> results = session.executeRead(tx->{
                var result = tx.run(query);
                return result.list();
            });

            List<String> xpaths = new ArrayList<>();
            for(Record r: results){
                xpaths.add(r.get(0).asString());
            }

            return xpaths;

        }
    }
}
