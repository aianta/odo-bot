package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class SelectOptionNode extends NavNode{

    private String xpath;

    public static SelectOptionNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        SelectOptionNode result = fromRecord(record, new SelectOptionNode());
        result.setXpath(n.get("xpath").asString());

        return result;
    }

    public String getXpath() {
        return xpath;
    }

    public SelectOptionNode setXpath(String xpath) {
        this.xpath = xpath;
        return this;
    }
}
