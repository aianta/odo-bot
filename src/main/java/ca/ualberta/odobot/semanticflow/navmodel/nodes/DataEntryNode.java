package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;


public class DataEntryNode extends NavNode {

    private String xpath;

    public static DataEntryNode fromRecord(Record record){

        Node n  = record.get(0).asNode();

        DataEntryNode result = fromRecord(record, new DataEntryNode());
        result.setXpath(n.get("xpath").asString());

        return  result;
    }


    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }
}
