package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Query;
import org.neo4j.driver.Record;

public class EndNode extends NavNode{

    /**
     * This method is critical as it is called via reflection by {@link ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils#readNode(Query, Class)}
     * to get nodes from the database.
     * @param record
     * @return
     */
    public static EndNode fromRecord(Record record){
        EndNode result = fromRecord(record, new EndNode());
        return result;
    }
}
