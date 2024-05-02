package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class ApplicationLocationChangeNode extends NavNode{

    private String from;
    private String to;

    public static ApplicationLocationChangeNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        ApplicationLocationChangeNode result = new ApplicationLocationChangeNode();
        result.setId(UUID.fromString(n.get("id").asString()));
        result.setFrom(n.get("from").asString());
        result.setTo(n.get("to").asString());
        result.setInstances(n.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));

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
