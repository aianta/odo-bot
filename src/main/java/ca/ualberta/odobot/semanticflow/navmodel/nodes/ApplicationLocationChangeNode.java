package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class ApplicationLocationChangeNode extends NavNode {

    private String from;
    private String to;

    public static ApplicationLocationChangeNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        ApplicationLocationChangeNode result = fromRecord(record, new ApplicationLocationChangeNode());
        result.setFrom(n.get("from").asString());
        result.setTo(n.get("to").asString());

        return result;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }
}
