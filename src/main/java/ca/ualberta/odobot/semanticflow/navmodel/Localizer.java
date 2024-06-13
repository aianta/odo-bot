package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.guidance.PathsRequestInput;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;


public class Localizer {

    private static final Logger log = LoggerFactory.getLogger(Localizer.class);

    private GraphDatabaseService db;

    private Map<DynamicXPath, UUID> dynamicXPathLocationIndex = new HashMap<>();
    private Map<String, UUID> xPathLocationIndex = new HashMap<>();

    public Localizer(GraphDB graphDB){
        this.db = graphDB.db;
        refreshIndices();
    }

    public void refreshIndices(){
        xPathLocationIndex = buildXpathLocationIndex();
        dynamicXPathLocationIndex = buildDynamicXPathLocationIndex();
    }

    private Map<DynamicXPath, UUID> buildDynamicXPathLocationIndex(){

        Map<DynamicXPath, UUID> index = new HashMap<>();

        executeNodesQuery("match (n) where n:CollapsedClickNode or n:CollapsedDataEntryNode return n;", "n",
                (node)->{
                    DynamicXPathIndexEntry entry = buildDynamicXPathIndexEntry(node);
                    index.put(entry.dynamicXPath(), entry.nodeId());
                });

        return index;

    }


    private Map<String, UUID> buildXpathLocationIndex(){
        Map<String,UUID> index = new HashMap<>();

        executeNodesQuery("match (n) where (n:ClickNode OR n:DataEntryNode) AND NOT n:CollapsedClickNode AND NOT n:CollapsedDataEntryNode return n;", "n",
                (node)->{
                    XPathIndexEntry entry = buildXpathIndexEntry(node);
                    index.put(entry.xpath(), entry.nodeId());
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

    private record XPathIndexEntry(String xpath, UUID nodeId){};

    private XPathIndexEntry buildXpathIndexEntry(Node node){
        return new XPathIndexEntry(
                (String)node.getProperty("xpath"),
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


    public Optional<UUID> resolveStartingNode(PathsRequestInput input){


        if(input.getLastEntity() == null){
            //No last entity because of sparse/minimal/empty local context. Need to resolve using url.
            //TODO -> make sure URL is sent even without local context.
            //TODO -> implement a method for resolving using location.
            return Optional.empty();
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

        return findNodeIdByXPath(xpath);
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
