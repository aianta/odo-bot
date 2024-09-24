package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class APINode extends NavNode {

    //TODO - Sadly, path and method are not sufficiently differentiating, will have to deal with that at some point.
    private String path;

    private String method;


    public static APINode fromRecord(Record record){
        Node n = record.get(0).asNode();

        APINode result = fromRecord(record, new APINode());
        result.setMethod(n.get("method").asString());
        result.setPath(n.get("path").asString());

        return result;
    }



    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }
}
