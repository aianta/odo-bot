package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class LocationNode extends NavNode {

    private String path;

    public static LocationNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        LocationNode result = fromRecord(record, new LocationNode());
        result.setPath(n.get("path").asString());

        return result;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
