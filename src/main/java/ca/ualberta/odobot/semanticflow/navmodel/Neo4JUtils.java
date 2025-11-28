package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.mind2web.*;
import ca.ualberta.odobot.semanticflow.model.*;

import ca.ualberta.odobot.semanticflow.navmodel.nodes.*;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import io.vertx.core.json.JsonObject;
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
     * A wrapper method for {@link #processDataEntry(String, String, String, String)} to simplify processing {@link DataEntry}s.
     * @param timeline
     * @param dataEntry
     */
    public void processDataEntry(Timeline timeline, DataEntry dataEntry){
        var index = timeline.indexOf(dataEntry);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        log.info("DataEntry:\n{}", dataEntry.toJson().encodePrettily());
        log.info("{}", dataEntry.lastChange().getBasePath());

        if(dataEntry.lastChange() instanceof TinymceEvent){
            processDataEntry(dataEntry.xpath(), ((TinymceEvent) dataEntry.lastChange()).getEditorId(), entityTimelineId, dataEntry.lastChange().getBasePath());
        }else{
            processDataEntry(dataEntry.xpath(), null, entityTimelineId,  dataEntry.lastChange().getBasePath());
        }


    }

    /**
     * A wrapper method for {@link #processCheckboxEvent(String, String, String)} to simplify processing {@link CheckboxEvent}s.
     * @param timeline
     * @param checkboxEvent
     */
    public void processCheckboxEvent(Timeline timeline, CheckboxEvent checkboxEvent){
        var index = timeline.indexOf(checkboxEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        processCheckboxEvent(checkboxEvent.xpath(), entityTimelineId, checkboxEvent.getBasePath());
    }

    public void processRadioButtonEvent(Timeline timeline, RadioButtonEvent radioButtonEvent){
        var index = timeline.indexOf(radioButtonEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        processRadioButtonEvent(radioButtonEvent.getXpath(), entityTimelineId, radioButtonEvent.getRadioGroup(), radioButtonEvent.getOptions(), radioButtonEvent.getBasePath());
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


    public void processSelectOptionWithWebsite(SelectOption selectOption, String website){
        processSelectOptionWithWebsite(selectOption.getTargetElementXpath(), selectOption.getActionId(), website);
    }

    public void processSelectOptionWithWebsite(String xpath, String eventId, String website){
        //If a select option node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<SelectOptionNode> existingSelectOptionNodeSupplier = ()-> getSelectOptionNodeWithWebsite(xpath, website);

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

    public void processSelectOption(String xpath, String eventId, String basePath){

        //If a select option node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<SelectOptionNode> existingSelectOptionNodeSupplier = ()->getSelectOptionNode(xpath, basePath);


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


    public void processRadioButtonEvent(String xpath, String eventId, String radioGroup, List<RadioButtonEvent.RadioButton> relatedElements, String basePath){

        log.info("Processing RadioButtonEvent {} [xpath: {}, radioGroup: {}]", eventId, xpath, radioGroup);

        //If a radiobutton node for this event already exists in the database, this supplier will be used to retrieve it;
        Supplier<RadioButtonNode> existingRadioButtonNodeSupplier = ()->getRadioButtonNode(xpath, radioGroup, basePath);

        RadioButtonNode node = existingRadioButtonNodeSupplier.get();

        if(node == null){ //Node cannot be found.
            //Create it.
            node = newRadioButtonNodeSupplier(eventId, radioGroup, relatedElements, basePath).get();
        }else{
            //If the node was found, update its list of instances.
            node.getInstances().add(eventId);
        }

        //Write changes to the node back into the database
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            //Try and find the existing node in the database.
            RadioButtonNode _existing = getRadioButtonNode(xpath, radioGroup, basePath);
            if(_existing != null){
                log.info("Updating existing RadioButtonNode");

                //Update its instances
                var stmt = "MATCH (n:RadioButtonNode {id:$id}) SET n.instances = $instances RETURN n";
                var query = new Query(stmt, parameters("id",_existing.getId().toString(), "instances", node.getInstances()));
                session.executeWrite(tx->{
                    tx.run(query);
                    return 0;
                });
            }else{
                log.info("Creating new RadioButtonNode");

                //Sanity check
                log.info("Event xpath exists in RadioButtonNode's xpath: {}", node.getXpaths().contains(xpath));
                assert node.getXpaths().contains(xpath);

                //No existing node. Create one now.
                HashMap<String, Object> props = new HashMap<>();
                props.put("xpaths", node.getXpaths());
                props.put("basePath", node.getBasePath());
                props.put("id", node.getId().toString());
                props.put("instances", node.getInstances());
                props.put("radioGroup", node.getRadioGroup());
                props.put("relatedElements", node.getButtonsAsStrings());

                var stmt = "CREATE (n:RadioButtonNode) SET n = $props RETURN n;";
                var query = new Query(stmt, parameters( "props", props));
                session.executeWrite(tx->{
                    tx.run(query);
                    return 0;
                });
            }

        }


    }

    public void processCheckboxEvent(String xpath, String eventId, String basePath){
        //If a checkbox node for this event already exists in the database, this supplier will be used to retrieve it;
        Supplier<CheckboxNode> existingCheckboxNodeSupplier = ()->getCheckboxNode(xpath, basePath);

        //Invoke generic processing logic
        processNode(
                eventId,
                CheckboxNode.class,
                existingCheckboxNodeSupplier,
                newCheckboxNodeSupplier(xpath, eventId, basePath),
                processCheckboxNodeQueryFunction()
        );


    }

    /**
     *
     * @param xpath
     * @param editorId tinyMCE editor id, only defined if this data entry interaction originated from there.
     * @param eventId
     */
    public void processDataEntry(String xpath, String editorId, String eventId, String basePath){

        //If a data entry node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<DataEntryNode> existingDataEntryNodeSupplier = ()->getDataEntryNode(xpath, basePath);

        //Invoke generic processing logic.
        processNode(
                eventId,
                DataEntryNode.class,
                existingDataEntryNodeSupplier, //If a data entry node for this event already exists in the database, this supplier will be used to retrieve it.
                newDataEntryNodeSupplier(xpath, editorId, eventId, basePath), //If no data entry node could be found, this supplier will be used to create one
                processDataEntryNodeQueryFunction()//The update query used to update/merge the processed data entry node into the database.
        );

    }


    private Function<CheckboxNode, Query> processCheckboxNodeQueryFunction(){

        return (checkboxNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", checkboxNode.getXpath());
            props.put("id", checkboxNode.getId().toString());
            props.put("instances", checkboxNode.getInstances());
            props.put("basePath", checkboxNode.getBasePath());

            return makeGenericMergeQuery("CheckboxNode", checkboxNode, props, "xpath", checkboxNode.getXpath(), "props", props);
        };

    }

    private Function<DataEntryNode, Query> processDataEntryNodeQueryFunction(){

        Function<DataEntryNode, Query> queryFunction = (dataEntryNode)->{
            HashMap<String, Object> props = new HashMap<>();
            props.put("xpath", dataEntryNode.getXpath());
            props.put("basePath", dataEntryNode.getBasePath());
            props.put("id", dataEntryNode.getId().toString());
            props.put("instances", dataEntryNode.getInstances());

            if (dataEntryNode.getEditorId() != null) {
                props.put("editorId", dataEntryNode.getEditorId());
            }

            return makeGenericMergeQuery("DataEntryNode", dataEntryNode, props, "xpath", dataEntryNode.getXpath(), "basePath", dataEntryNode.getBasePath(),  "props", props);
        };

        return queryFunction;
    }


    private Supplier<RadioButtonNode> newRadioButtonNodeSupplier(String eventId, String radioGroup, List<RadioButtonEvent.RadioButton> relatedElements, String basePath){
        //If no radiobutton node could be found, this supplier will be used to create one.
        return ()->{
            RadioButtonNode node = new RadioButtonNode();
            node.setId(UUID.randomUUID());
            node.setInstances(Set.of(eventId));
            node.setRadioGroup(radioGroup);
            node.setRadioButtons(relatedElements);
            node.setBasePath(basePath);
            return node;
        };

    }

    private Supplier<CheckboxNode> newCheckboxNodeSupplier(String xpath, String eventId, String basePath){
        //If no checkbox node could be found, this supplier will be used to create one.
        return ()->{
            CheckboxNode node = new CheckboxNode();
            node.setId(UUID.randomUUID());
            node.setXpath(xpath);
            node.setBasePath(basePath);
            node.setInstances(Set.of(eventId));
            return node;
        };
    }


    private Supplier<DataEntryNode> newDataEntryNodeSupplier (String xpath, String editorId, String eventId, String basePath){
        //If no data entry node could be found, this supplier will be used to create one
        Supplier<DataEntryNode> newDataEntryNodeSupplier = ()->{
            DataEntryNode node = new DataEntryNode();
            node.setId(UUID.randomUUID());
            node.setXpath(xpath);
            node.setBasePath(basePath);
            node.setInstances(Set.of(eventId));

            if (editorId != null) {
                node.setEditorId(editorId);
            }

            return node;
        };
        return newDataEntryNodeSupplier;
    }



    public void processClick(String clickText, String clickXpath, String eventId, String basePath){

        //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
        Supplier<ClickNode> existingClickNodeSupplier = ()->getClickNode(clickXpath, clickText, basePath);

        //Invoke generic processing logic.
        processNode(
                eventId,
                ClickNode.class,
                existingClickNodeSupplier, //If a click node for this event already exists in the database, this supplier will be used to retrieve it.
                newClickNodeSupplier(clickText, clickXpath, eventId, basePath), //If no click node could be found, this supplier will be used to create one
                processClickQueryFunction()
        );
    }


    /**
     *  Supplies new click nodes
     * @return
     */
    private Supplier<ClickNode> newClickNodeSupplier(String clickText, String clickXpath, String eventId, String basePath){
        Supplier<ClickNode> newClickNodeSupplier = ()->{
            ClickNode clickNode = new ClickNode();
            clickNode.setId(UUID.randomUUID());
            clickNode.setText(clickText);
            clickNode.setXpath(clickXpath);
            clickNode.setBasePath(basePath);
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
            props.put("basePath", clickNode.getBasePath());
            props.put("text", clickNode.getText());
            props.put("id", clickNode.getId().toString());
            props.put("instances", clickNode.getInstances());

            return makeGenericMergeQuery("ClickNode", clickNode, props, "xpath", clickNode.getXpath(), "text", clickNode.getText(), "props", props);

        };
        return queryFunction;
    }


    /**
     * Helper method that returns a cypher query to match or create a specific node based on its property values.
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
        }else if (node instanceof XpathAndBasePathNode){
            props.put("basePath", ((XpathAndBasePathNode)node).getBasePath());
            map.put("basePath", ((XpathAndBasePathNode)node).getBasePath());
            String[] stringProps = map.keySet().stream()
                    .filter(k->!k.equals("props")) //'props' should not be a property value, it is passed so we can use it in the parameters() call, and it represents node properties as a whole, but is not in fact a property 'props' with corresponding map value.
                    .toArray(String[]::new); //Convert matching properties to a string array for use with makeSimpleNodeQuerySegment
            stmt = "MERGE "+ makeSimpleNodeQuerySegment("n", nodeLabel, stringProps) + " ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            query = new Query(stmt, parameters(mapToKeysAndValues(map)));
        } else{
            String [] stringProps = map.keySet().stream()
                    .filter(k->!k.equals("props")) //'props' should not be a property value, it is passed so we can use it in the parameters() call.
                    .toArray(String[]::new); //Convert matching properties to a string array for use with makeSimpleNodeQuerySegment
            stmt = "MERGE "+ makeSimpleNodeQuerySegment("n", nodeLabel, stringProps) + " ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
            query = new Query(stmt, parameters(keysAndValues));
        }

        log.info("Merge Query:\n{}", stmt); //For debugging

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



    public void processClickEvent(Timeline timeline, ClickEvent clickEvent){
        var index  = timeline.indexOf(clickEvent);
        var entityTimelineId = timeline.getId().toString()+"#"+index;

        String clickText = clickEvent.getTriggerElement() != null?clickEvent.getTriggerElement().ownText():"";
        String clickXpath = clickEvent.getXpath();

        processClick(clickText, clickXpath, entityTimelineId, clickEvent.getBasePath());
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
            return getClickNodeWithWebsite(click.getTargetElementXpath(), click.targetElement().ownText(), website);
        }

        if(operation instanceof Type){
            Type type = (Type) operation;
            return getDataEntryNodeWithWebsite(type.getTargetElementXpath(), website);
        }

        if(operation instanceof SelectOption){
            SelectOption selectOption = (SelectOption) operation;
            return getSelectOptionNodeWithWebsite(selectOption.getTargetElementXpath(), website);
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




    public NavNode resolveNavNode(Timeline timeline, int index){
        log.info("Attempting to resolve {}[{}]", timeline.get(index).symbol(), index);

        TimelineEntity target = timeline.get(index);
        if (target instanceof ClickEvent){
            ClickEvent clickEvent = (ClickEvent)target;
            return getClickNode(clickEvent.getXpath(), clickEvent.getTriggerElement() != null ? clickEvent.getTriggerElement().ownText():"", clickEvent.getBasePath());
        }

        if(target instanceof DataEntry){
            DataEntry dataEntry = (DataEntry) target;
            return getDataEntryNode(dataEntry.xpath(), dataEntry.lastChange().getBasePath());
        }

        if(target instanceof RadioButtonEvent){
            RadioButtonEvent radioButtonEvent = (RadioButtonEvent) target;
            log.info("RadioButtonNode with xpath: {}", radioButtonEvent.getXpath());
            return getRadioButtonNode(radioButtonEvent.getXpath(), radioButtonEvent.getRadioGroup(), radioButtonEvent.getBasePath());
        }

        if(target instanceof CheckboxEvent){
            CheckboxEvent checkboxEvent = (CheckboxEvent) target;
            return getCheckboxNode(checkboxEvent.xpath(), checkboxEvent.getBasePath());
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

        if(predecessor instanceof RadioButtonNode){
            stmt = "MATCH (a:RadioButtonNode {id:$aId})-[:NEXT]->(e:EffectNode)";
        }

        if(predecessor instanceof CheckboxNode){
            stmt = "MATCH (a:CheckboxNode {id:$aId})-[:NEXT]->(e:EffectNode)";
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

        if(successor instanceof RadioButtonNode){
            stmt += "-[:NEXT]->(b:RadioButtonNode {id:$bId}) RETURN e;";
        }

        if(successor instanceof CheckboxNode){
            stmt+="-[:NEXT]->(b:CheckboxNode {id:$bId}) RETURN e;";
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

        if (successor instanceof RadioButtonNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:RadioButtonNode {id:$id}) RETURN e;";
        }

        if(successor instanceof CheckboxNode){
            stmt = "MATCH (e:EffectNode)-[:NEXT]->(n:CheckboxNode {id:$id}) RETURN e;";
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

        if(predecessor instanceof RadioButtonNode){
            stmt = "MATCH (n:RadioButtonNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
        }

        if(predecessor instanceof CheckboxNode){
            stmt = "MATCH (n:CheckboxNode {id:$id})-[:NEXT]->(e:EffectNode) RETURN e;";
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

    private SelectOptionNode getSelectOptionNode(String xpath, String basePath){
        var stmt = makeSimplePropertyBasedMatchQueryString("SelectOptionNode", "xpath", "basePath");
        var query = new Query(stmt, parameters("xpath", xpath, "basePath", basePath));

        return readNode(query, SelectOptionNode.class);
    }

    private SelectOptionNode getSelectOptionNodeWithWebsite(String xpath, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("SelectOptionNode", "xpath", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "website", website));

        log.info("getSelectOptionNode Query: {}", stmt);

        return readNode(query, SelectOptionNode.class);
    }

    private RadioButtonNode getRadioButtonNode(String xpath, String radioGroup, String basePath){
        var stmt = "MATCH (n:RadioButtonNode {radioGroup:$radioGroup, basePath:$basePath}) WHERE $xpath IN n.xpaths return n";
        var query = new Query(stmt, parameters("xpath", xpath, "radioGroup", radioGroup, "basePath", basePath));
        return readNode(query, RadioButtonNode.class);
    }

    private CheckboxNode getCheckboxNode(String xpath, String basePath){
        var stmt = makeSimplePropertyBasedMatchQueryString("CheckboxNode", "xpath", "basePath");
        var query = new Query(stmt, parameters("xpath", xpath, "basePath", basePath));
        return readNode(query, CheckboxNode.class);
    }

    private CheckboxNode getCheckboxNodeWithWebsite(String xpath, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("CheckboxNode", "xpath", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "website", website));
        return readNode(query, CheckboxNode.class);
    }

    private DataEntryNode getDataEntryNode(String xpath, String basePath){
        var stmt =  makeSimplePropertyBasedMatchQueryString("DataEntryNode", "xpath", "basePath");
        var query = new Query(stmt, parameters("xpath", xpath, "basePath", basePath));
        return readNode(query, DataEntryNode.class);
    }

    private DataEntryNode getDataEntryNodeWithWebsite(String xpath, String website){
        var stmt = makeSimplePropertyBasedMatchQueryString("DataEntryNode", "xpath", "website");
        var query = new Query(stmt, parameters("xpath", xpath, "website", website));
        return readNode(query, DataEntryNode.class);
    }

    private ClickNode getClickNode(String xpath, String text, String basePath){
        var stmt = makeSimplePropertyBasedMatchQueryString("ClickNode", "xpath", "text", "basePath");
        var query = new Query(stmt, parameters("xpath", xpath, "text", text, "basePath", basePath));
        return readNode(query, ClickNode.class);

    }

    private ClickNode getClickNodeWithWebsite(String xpath, String text, String website){
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

    public List<NavNode> getNodesForInputParameters(){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String sQuery = """
                    match (n) WHERE n:DataEntryNode OR n:CheckboxNode OR n:RadioButtonNode return n;
                    """;
            Query query = new Query(sQuery);

            List<Record> results = session.executeRead(tx->{
                var result = tx.run(query);
                return result.list();
            });

            List<NavNode> nodes = results.stream()
                    .map(record->{
                        var node = record.get(0).asNode();
                        if(node.hasLabel("DataEntryNode")){
                            return DataEntryNode.fromRecord(record);
                        }else if(node.hasLabel("CheckboxNode")){
                            return CheckboxNode.fromRecord(record);
                        } else if(node.hasLabel("RadioButtonNode")){
                            return RadioButtonNode.fromRecord(record);
                        }else{
                            return null;
                        }
                    })
                    .collect(Collectors.toList());

            return nodes;
        }
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

    public <T extends NavNode> List<T> readNodes(Query query, Class<T> tClass){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            List<Record> nodes = session.executeRead(tx->{
                var result = tx.run(query);
                return result.list();
            });

            if(nodes != null){
                return nodes.stream()
                        .map(record-> {
                            try {
                                return (T) tClass.getMethod("fromRecord", Record.class).invoke(null, record);
                            } catch (IllegalAccessException e) {
                                throw new RuntimeException(e);
                            } catch (InvocationTargetException e) {
                                throw new RuntimeException(e);
                            } catch (NoSuchMethodException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .collect(Collectors.toList());
            }

            log.error("Error reading list of nodes!");
            return null;
        }
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

    public String getXpathFromActionId(String actionId){
        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String xpath = session.executeRead(tx->{

                var _query = """
                        MATCH (n) where $actionId in n.instances return n.xpath
                        """;

                Query query = new Query(_query, parameters("actionId", actionId));

                var result = tx.run(query);

                return result.single().get(0).asString();
            });

            return xpath;
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


    /**
     * Get the xpaths associated with the actions of a trajectory/mind2web task.
     *
     * @param actionIds a list of mind2web action_uids corresponding to a Synapse trajectory/mind2web task (identified by annotation_id).
     * @return A list of the xpaths belonging to nodes containing one or more the provided action_uids in their instances property.
     */
    public List<String> getXpathsForTrajectory(List<String> actionIds){
        String sQuery = """
                match (n) with 
                    n.instances as instances,
                    n
                    where any(action in $action_ids where action in instances) 
                    return n.xpath;
                """;

        Query query = new Query(sQuery, parameters("action_ids", actionIds));

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

    public String getAssociatedParameterId(String nodeId){
        String sQuery = """
                match (m)-[:PARAM]->(n) WHERE (n:InputParameter OR n:SchemaParameter) AND m.id = $id RETURN n.id; 
                """;
        Query query = new Query(sQuery, parameters("id", nodeId));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String id = session.executeRead(tx->{
                var result = tx.run(query);
                return result.single().get(0).asString();
            });

            return id;
        }
    }

    public Set<String> getParameterAssociatedNodes(String parameterNodeId){

        String sQuery = """
                match (n)<-[:PARAM]-(m) WHERE (n:InputParameter OR n:SchemaParameter) AND n.id = $id RETURN m.id;
                """;

        Query query = new Query(sQuery, parameters("id", parameterNodeId));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            Set<String> ids = session.executeRead(tx->{
                var result = tx.run(query);
                return result.list().stream().map(record->record.get(0).asString()).collect(Collectors.toSet());
            });

            return ids;
        }

    }

    public String getNodeIdBySchemaName(String name){
        String sQuery = "match (n:SchemaParameter) where n.name = $name return n.id;";
        Query query = new Query(sQuery, parameters("name", name));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String id = session.executeRead(tx->{
                var result = tx.run(query);
                return result.single().get(0).asString();
            });

            return id;
        }
    }

    public String getNodeIdBySchemaId(String schemaId){
        String sQuery = "match (n:SchemaParameter) where n.schemaId = $schemaId return n.id;";
        Query query = new Query(sQuery, parameters("schemaId", schemaId));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String id = session.executeRead(tx->{
                var result = tx.run(query);
                return result.single().get(0).asString();
            });

            return id;
        }
    }

    /**
     * Returns the id of a non-parameter node matching a particular xpath
     *
     * This is used to look up input parameters vertices during input parameter mapping
     * @param xpath
     * @return
     */
    public String getNonParameterNodeIdByXpath(String xpath){
        String sQuery = """
                match (n)  WHERE NOT n:SchemaParameter AND NOT n:InputParameter AND n.xpath = $xpath return n.id;
                """;
        Query query = new Query(sQuery, parameters("xpath", xpath));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String id = session.executeRead(tx->{
                var result = tx.run(query);

                return result.single().get(0).asString();
            });

            return id;
        }
    }

    public List<APINode> getAllAPINodes(){
        String sQuery = """
                match (n:APINode) return n;
                """;
        Query query = new Query(sQuery);

        return readNodes(query, APINode.class);
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

    public UUID addSchemaParameter(SemanticSchema schema, String nodeId){

        UUID parameterNodeId = UUID.randomUUID();

        HashMap<String, Object> props = new HashMap<>();
        props.put("id", parameterNodeId.toString());
        props.put("schemaId", schema.getId().toString());
        props.put("dynamicXpathId", schema.getDynamicXpathId());
        props.put("name", schema.getName());
        props.put("xml", schema.getSchema());

        //TODO: Probably shouldn't merge schemas by name. As the number of schema params grow, there is bound to be clashes between schema parameters that do not represent the same things, but are called the same.
        //TODO: Schema parameter merging should probably be its own distinct step, where the regions of the model where the parameter was detected is taken into consideration, amongst other heuristics.
        //TODO: Nevertheless, for now, for Candidacy, let's take the naive approach.
        //var createParameterNodeStmt = "MERGE (n:SchemaParameter {schemaId:$schemaId}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
        var createParameterNodeStmt = "MERGE (n:SchemaParameter {name:$name}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";

        Query createParameterNodeQuery = new Query(createParameterNodeStmt, parameters("name", schema.getName(), "props", props));

        var createRelationshipStmt = """
                MATCH (sourceNode {id:$sourceNodeId}), (parameterNode:SchemaParameter {id:$parameterNodeId}) 
                CREATE (sourceNode)-[:PARAM]->(parameterNode);
            """;

        Query createRelationshipQuery = new Query(createRelationshipStmt, parameters("sourceNodeId", nodeId, "parameterNodeId", parameterNodeId.toString()));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){

            session.executeWrite(tx->{
               tx.run(createParameterNodeQuery);
               tx.run(createRelationshipQuery);
               return 0;
            });
        }

        return parameterNodeId;

    }

    public String getInputParameterId(String label){
        String sQuery = "match (n:InputParameter) where n.label = $label return n.id;";
        Query query = new Query(sQuery, parameters("label", label));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String id = session.executeRead(tx->{
                var result = tx.run(query);
                return result.single().get(0).asString();
            });

            return id;
        }
    }

    public UUID addInputParameter(JsonObject parameter, String nodeId){

        UUID parameterNodeId = UUID.randomUUID();

        HashMap<String, Object> props = new HashMap<>();
        props.put("id", parameterNodeId.toString());
        props.put("label", parameter.getString("label"));
        props.put("description", parameter.getString("description"));
        props.put("xpath", parameter.getString("xpath"));

        //TODO: Unclear how good of an idea it is to merge input parameters by their names directly, this probably needs more nuance. But merging by xpath no longer makes sense since the base path update anyways.
//
//        var createParameterNodeStmt = "MERGE (n:InputParameter {xpath:$xpath}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";
        var createParameterNodeStmt = "MERGE (n:InputParameter {label:$label}) ON CREATE SET n = $props ON MATCH SET n = $props RETURN n;";

        Query createParameterNodeQuery = new Query(createParameterNodeStmt, parameters("label", parameter.getString("label"), "props", props));

        var createRelantionshipStmt = """
                MATCH (sourceNode {id:$sourceNodeId}), (parameterNode:InputParameter {id:$parameterNodeId})
                CREATE (sourceNode)-[:PARAM]->(parameterNode);
                """;

        Query createRelationshipQuery = new Query(createRelantionshipStmt, parameters("sourceNodeId", nodeId, "parameterNodeId", parameterNodeId.toString()));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            session.executeWrite(tx->{
                tx.run(createParameterNodeQuery);
                tx.run(createRelationshipQuery);
                return  0;
            });
        }

        return parameterNodeId;
    }

    /**
     * Returns a map of [NodeId][ParameterNodeId] pairs.
     * @return
     */
    public Map<String,String> getGlobalParameterMap(){

        var schemaParameterQuery = """
                MATCH (n:SchemaParameter)<-[:PARAM]-(m) return n.id as schemaParamId, m.id as nodeId; 
                """;

        Query _schemaParameterQuery = new Query(schemaParameterQuery);


        var inputParameterQuery = """
                MATCH (n:InputParameter)<-[:PARAM]-(m) return n.id as schemaParamId, m.id as nodeId; 
                """;

        Query _inputParameterQuery = new Query(inputParameterQuery);

        Map<String, String> result = new HashMap<>();

        result.putAll(executeParameterMapQuery(_schemaParameterQuery));
        result.putAll(executeParameterMapQuery(_inputParameterQuery));

        return result;
    }

    public String getSchemaId(String parameterNodeId){
        var _query = """
                MATCH (n:SchemaParameter) WHERE n.id = $nodeId RETURN n.schemaId as schemaId;
                """;
        Query query = new Query(_query, parameters("nodeId", parameterNodeId));

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            String schemaId = session.executeRead(tx->{
                Result result = tx.run(query);
                return result.single().get("schemaId").asString();

            });

            return schemaId;
        }

    }

    private Map<String,String> executeParameterMapQuery(Query query){


        Map<String,String> map = new HashMap<>();

        try(var session = driver.session(SessionConfig.forDatabase(databaseName))){
            session.executeRead(tx->{
                Result result = tx.run(query);

                while (result.hasNext()){
                    Record record = result.next();

                    map.put(
                            record.get("nodeId").asString(),
                            record.get("schemaParamId").asString()
                    );
                }

                return 0;
            });
        }

        return map;
    }
}
