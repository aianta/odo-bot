package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class LocationNode extends NavNode{

    private String path;

    public static LocationNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        LocationNode result = new LocationNode();
        result.setId(UUID.fromString(n.get("id").asString()));
        result.setPath(n.get("path").asString());
        result.setInstances(n.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));

        return result;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
