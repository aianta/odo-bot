package ca.ualberta.odobot.semanticflow.navmodel.nodes;


import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
public class StartNode extends NavNode{

    /**
     * This method is critical as it is called via reflection by {@link ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils#readNode(Query, Class)}
     * to get nodes from the database.
     * @param record
     * @return
     */
    public static StartNode fromRecord(Record record){
        StartNode result = fromRecord(record, new StartNode());
        return result;
    }
}
