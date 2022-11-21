package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.neo4j.driver.Values.parameters;

public class Neo4JParser {

    private static final Logger log = LoggerFactory.getLogger(Neo4JParser.class);
    private final Driver driver;

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

    public void linkNodes(String xpath1, String value1, String xpath2, String value2, double probability, int observations, int totalObservations){
        try(var session = driver.session()){
            session.executeWrite(tx->{
                var queryString = "MATCH (n1 {xpath:\""+xpath1+"\", value:$value1}), " +
                        "(n2 {xpath:\""+xpath2+"\", value:$value2}) " +
                        "CREATE (n1)-[r:observes {probability: "+probability+", observations: "+observations+",  totalObservations: "+totalObservations+" }]->(n2); ";
                log.info(queryString);
               var query = new Query(queryString, parameters("value1", value1, "value2", value2));
               tx.run(query);
               return 0;
            });
        }
    }

}
