package ca.ualberta.odobot.semanticflow.navmodel;

import ca.ualberta.odobot.semanticflow.model.DataEntry;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class DataEntryNode extends NavNode {

    private String xpath;

    public static DataEntryNode fromRecord(Record record){

        Node n  = record.get(0).asNode();

        DataEntryNode result = new DataEntryNode();
        result.setId(UUID.fromString(n.get("id").asString()));
        result.setXpath(n.get("xpath").asString());
        result.setInstances(n.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));

        return  result;
    }


    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }
}
