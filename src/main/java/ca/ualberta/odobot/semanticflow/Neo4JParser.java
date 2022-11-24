package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.neo4j.driver.Values.parameters;

public class Neo4JParser {

    private static final Logger log = LoggerFactory.getLogger(Neo4JParser.class);
    private final Driver driver;
    private static final String DOM_ELEMENT_LABEL = "DOM_Element";
    private static final String EVENT_TARGET_LABEL = "Event_Target";
    private static final String DOM_VALUE_LABEL = "DOM_Value";

    public Neo4JParser(String uri, String user, String password){
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    
    public void createNode(String xpath, String value){
        try(var session = driver.session()){
            session.executeWrite(tx->{
                var queryString = "CREATE (n {xpath: \""+xpath+"\", value:$value}) RETURN n; ";
                log.info(queryString);
                var query = new Query(queryString, parameters("value", value));
                var result = tx.run(query);
                return 0;
            });
        }
    }

    public void addValueNode(String xpath, String value){
        try(var session = driver.session()){
            session.executeWrite(tx->{
                var queryString =
                        "MATCH (n1:"+DOM_ELEMENT_LABEL+":"+EVENT_TARGET_LABEL+" {path:$path1})" +
                                "MERGE (n1)-[r1:value]->(v1:"+DOM_VALUE_LABEL+" {value:$value1}) RETURN *; ";
                var query = new Query(queryString, parameters("path1",xpath, "value1", value));
                tx.run(query);
                return 0;
            });
        }
    }

    public void linkNodes(String xpath1, String value1, String xpath2, String value2, double probability, int observations, int totalObservations){
        //Create value nodes
        addValueNode(xpath1, value1);
        addValueNode(xpath2, value2);

        //Bind value nodes together
        try(var session = driver.session()){
            session.executeWrite(tx->{

                var queryString =
                        "MATCH (n1:"+DOM_ELEMENT_LABEL+":"+EVENT_TARGET_LABEL+" {path:$path1})-[:value]->(v1:"+DOM_VALUE_LABEL+" {value:$value1}), (n2:"+DOM_ELEMENT_LABEL+":"+EVENT_TARGET_LABEL+" {path:$path2})-[:value]->(v2:"+DOM_VALUE_LABEL+" {value:$value2}) " +
                                "CREATE (v1)-[r:observes {probability: "+probability+", observations: "+observations+",  totalObservations: "+totalObservations+" }]->(v2) RETURN *; ";

               var query = new Query(queryString, parameters("path1", xpath1, "path2", xpath2, "value1", value1, "value2", value2));
               log.info("{}", query.toString());
               Result result = tx.run(query);
               //log.info("result:{}",result.single().toString());
               return 0;
            });
        }
    }

    public void materializeXpath(String path){
        log.info("Materializing path: {}", path);
        String [] components = path.split("/");
        List<String> componentList = Arrays.stream(components).toList();
        log.info("componentList: {}", componentList);

        ListIterator<String> parentIt = componentList.listIterator();
        ListIterator<String> childIt = componentList.listIterator();
        childIt.next();

        String parentPath = "/";
        String childPath = "/";
        while (childIt.hasNext()){
            String parent = parentIt.next();
            if(parent.equals("")){
                childIt.next();
                continue;
            }
            String child = childIt.next();
            if(child == null){
                break; //Nothing left to build.
            }

            int parentIndex = getIndex(parent);
            int childIndex = getIndex(child);

            parentPath += parent + ( parentIt.hasNext()?"/":"");
            childPath = parentPath + child + (childIt.hasNext()?"/":"");
            log.info("parent: {} path: {} child:{} path:{}", parent, parentPath, child, childPath);

            //Build xpath
            linkChild(parentPath, childPath,
                    (missingPath)->{
                        materializeComponent(parent, missingPath, parentIndex, false);
                    },
                    (missingPath)->{
                        materializeComponent(child, missingPath, childIndex, !childIt.hasNext());
                    }
            );

        }


    }


    private int getIndex(String xPathComponent){
        Pattern indexPattern = Pattern.compile("[0-9]+");
        Matcher matcher = indexPattern.matcher(xPathComponent);
        if(matcher.find()){
            return Integer.parseInt(matcher.group(0));
        }else{
            return 0;
        }
    }

    /**
     *
     * @param parentPath xpath of the parent node
     * @param childPath xpath of the child node
     * @param missingParent invoked with parent path if parent was missing during link
     * @param missingChild invoked with child path if child was missing during link
     */
    private void linkChild(String parentPath, String childPath, Consumer<String> missingParent, Consumer<String> missingChild){
        try(var session = driver.session()){
            session.executeWrite(tx->{
                var queryString = "MATCH (n:"+DOM_ELEMENT_LABEL+"), (m:" + DOM_ELEMENT_LABEL + ") WHERE n.path=$parent AND m.path=$child " +
                        "MERGE (n)-[r:child]->(m) RETURN count(r), count(n), count(m);";

                var query = new Query(queryString, parameters("parent", parentPath, "child", childPath));
                log.info(query.toString());
                Result result = tx.run(query);
                Record record = result.single();
                int countR = record.get("count(r)").asInt();
                int countN = record.get("count(n)").asInt();
                int countM = record.get("count(m)").asInt();
                log.info("Found {} parents, {} children, created {} relationships", countN, countM, countR);
                if(countR > 1 || countN > 1 || countM > 1){
                    try {
                        throw new Exception("DOM elements are not unique!");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                //No relationship/edge was created
                if(countR == 0){

                    if (countN == 0){
                        //Because the parent could not be found
                        log.info("Firing missing parent consumer");
                        missingParent.accept(parentPath);
                    }

                    if(countM == 0){
                        //Because the child could not be found
                        log.info("Firing missing child consumer");
                        missingChild.accept(childPath);
                    }

                    //Now let's try again, hopefully the consumers missingParent and missingChild have handled the situation.
                    result = tx.run(query);
                    record = result.single();
                    countR = record.get("count(r)").asInt();
                    countN = record.get("count(n)").asInt();
                    countM = record.get("count(m)").asInt();

                    log.info("Found {} parents, {} children, created {} relationships", countN, countM, countR);

                }



                return 0;
            });
        }
    }

    private void materializeComponent(String component, String path, int index, boolean isEventTarget){
        if (index != 0){
            component = component.substring(0, component.indexOf("["));
        }
        final var tag = component;
        try(var session = driver.session()){
            session.executeWrite(tx->{
                var labels = DOM_ELEMENT_LABEL + (isEventTarget?":"+EVENT_TARGET_LABEL:"");

                //TODO - be careful here, how does merge behave if an event target is an element along the path of a different xpath?
                var queryString = "MERGE (n:"+labels+" {tag:$tag, path:$path, index:$index}) RETURN n";
                var query = new Query(queryString, parameters("tag", tag, "path", path, "index", index ));
                log.info(query.toString());
                tx.run(query);
                return 0;
            });
        }
    }

}
