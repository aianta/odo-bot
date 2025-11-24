package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.guidance.PathsRequestInput;
import ca.ualberta.odobot.semanticflow.model.ApplicationLocationChange;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;


public class Localizer {

    private static final Logger log = LoggerFactory.getLogger(Localizer.class);

    private GraphDatabaseService db;

    private Map<String, UUID> networkRequestIndex = new HashMap<>();

    /**
     * Index for resolving userLocation urls to node ids. These will map to LocationNode
     */
    private Map<String, UUID> locationIndex = new HashMap<>();

    /**
     * Index for resolving DynamicXPaths to node ids. These will map to Collapsed nodes of some type.
     */
    private Map<DynamicXPath, UUID> dynamicXPathLocationIndex = new HashMap<>();

    /**
     * Index for resolving xpaths to node ids. These will map to non-Collapsed nodes of some type.
     */
    private Map<String, UUID> xPathLocationIndex = new HashMap<>();

    public Localizer(GraphDB graphDB){
        this.db = graphDB.db;
        refreshIndices();
    }

    public void refreshIndices(){
        xPathLocationIndex = buildXpathLocationIndex();
        dynamicXPathLocationIndex = buildDynamicXPathLocationIndex();
        locationIndex = buildLocationIndex();
        networkRequestIndex = buildNetworkEventIndex();
    }

    private Map<DynamicXPath, UUID> buildDynamicXPathLocationIndex(){

        Map<DynamicXPath, UUID> index = new HashMap<>();

        executeNodesQuery("match (n) where n:CollapsedClickNode or n:CollapsedDataEntryNode return n;", "n",
                (node)->{

                    //For debugging
                    log.info("Node:");
                    node.getLabels().forEach(label->log.info("\t{}", label.name()));
                    node.getAllProperties().forEach((key,value)->log.info("\t{}:{}", key, value.toString()));

                    DynamicXPathIndexEntry entry = buildDynamicXPathIndexEntry(node);
                    index.put(entry.dynamicXPath(), entry.nodeId());
                });

        return index;

    }

    private Map<String, UUID> buildNetworkEventIndex(){
        Map<String, UUID> index = new HashMap<>();
        executeNodesQuery("match (n:APINode) return n;", "n", node->{
            String method = (String)node.getProperty("method");
            String path = (String)node.getProperty("path");

            index.put(method+"-"+path, UUID.fromString((String)node.getProperty("id")));
        });

        return index;
    }

    private Map<String, UUID> buildLocationIndex(){
        Map<String, UUID> index = new HashMap<>();
        executeNodesQuery("match (n:LocationNode) return n;", "n", node->{
            StringKeyIndexEntry entry = buildLocationIndexEntry(node);
            index.put(entry.key(), entry.nodeId());
        });
        return index;
    }


    private Map<String, UUID> buildXpathLocationIndex(){
        Map<String,UUID> index = new HashMap<>();

        executeNodesQuery("match (n) where (n:ClickNode OR n:DataEntryNode) AND NOT n:CollapsedClickNode AND NOT n:CollapsedDataEntryNode return n;", "n",
                (node)->{
                    StringKeyIndexEntry entry = buildXpathIndexEntry(node);
                    index.put(entry.key(), entry.nodeId());
        });

        return index;
    }


    private record DynamicXPathIndexEntry(DynamicXPath dynamicXPath, UUID nodeId){};

    private DynamicXPathIndexEntry buildDynamicXPathIndexEntry(Node node){
        String [] xpaths = (String[]) node.getProperty("xpaths");
        DynamicXPath dynamicXPath = NavPath.findDynamicXPath(xpaths);

        return new DynamicXPathIndexEntry(
                dynamicXPath,
                UUID.fromString((String)node.getProperty("id"))
        );
    }

    private record StringKeyIndexEntry(String key, UUID nodeId){};

    private StringKeyIndexEntry buildXpathIndexEntry(Node node){
        return new StringKeyIndexEntry(
                (String)node.getProperty("xpath"),
                UUID.fromString((String)node.getProperty("id"))
        );
    }

    private StringKeyIndexEntry buildLocationIndexEntry(Node node){
        return new StringKeyIndexEntry(
                (String)node.getProperty("path"),
                UUID.fromString((String)node.getProperty("id"))
        );
    }


    /**
     * Helper method for executing queries that return single sets of nodes.
     * @param query the query to execute.
     * @param resultColumnName the name of the column containing nodes.
     * @param nodeConsumer the consumer to invoke for every node in the specified result column.
     */
    private void executeNodesQuery(String query, String resultColumnName , Consumer<Node> nodeConsumer){
        try(
                Transaction tx = db.beginTx();
                Result result = tx.execute(query);
                ResourceIterator<Node> nodes = result.columnAs(resultColumnName);
        ){

            while (nodes.hasNext()){
                Node node = nodes.next();
                nodeConsumer.accept(node);
            }

        }
    }

    public Optional<UUID> findNodeByNetworkEvent(NetworkEvent networkEvent){
        String key = networkEvent.getMethod() + "-" + networkEvent.getPath();
        Optional<UUID> result = networkRequestIndex.entrySet().stream()
                .filter(entry->entry.getKey().equals(key))
                .map(Map.Entry::getValue)
                .findAny();
        return result;
    }

    public Optional<UUID> findNodeByLocationChange(ApplicationLocationChange locationChange){
        return findNodeByLocation(locationChange.getToPath());
    }


    public Optional<UUID> resolveStartingNode(PathsRequestInput input){


        if(input.getLastEntity() == null){
            //No last entity because of sparse/minimal/empty local context. Need to resolve using url.
            //TODO -> make sure URL is sent even without local context.
            //TODO -> implement a method for resolving using location.
            //TODO -> this is a hard coded implementation for normalizing specifically canvas paths, need to refactor this for generalization purposes.
            try{
               URL url = new URL(input.getUserLocation());
               String location = url.getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");

               return findNodeByLocation(location);

            }catch (MalformedURLException e){
                log.error("Failed to parse userLocation: {}", input.getUserLocation());
                log.error(e.getMessage(), e);
                return Optional.empty();
            }
        }

        String xpath = null;
        if(input.getLastEntity() instanceof ClickEvent){
            //Last entity was a click event
            ClickEvent entity = (ClickEvent) input.getLastEntity();
            xpath = entity.getXpath();
        }

        if(input.getLastEntity() instanceof DataEntry){
            //Last entity was a data entry
            DataEntry entity = (DataEntry) input.getLastEntity();
            xpath = entity.lastChange().getXpath();
        }

        log.info("Localizing via xpath: {}", xpath);

        return findNodeIdByXPath(xpath);
    }

    private Optional<UUID> findNodeByLocation(String location){
        //Look for a match in the location index
        Optional<UUID> result = locationIndex.entrySet().stream()
                .filter(entry->entry.getKey().equals(location))
                .map(Map.Entry::getValue)
                .findAny();
        return result;
    }

    private Optional<UUID> findNodeIdByXPath(String xpath){
        //Look for a match in the dynamic xpath location index
        Optional<UUID> result =  dynamicXPathLocationIndex.entrySet().stream()
                .filter(entry->entry.getKey().matches(xpath))
                .map(Map.Entry::getValue)
                .findAny();

        //If one is found return it.
        if(result.isPresent()){
            return result;
        }

        //Otherwise look for a match in the xpath location index
        return Optional.ofNullable(xPathLocationIndex.get(xpath));

    }



}
