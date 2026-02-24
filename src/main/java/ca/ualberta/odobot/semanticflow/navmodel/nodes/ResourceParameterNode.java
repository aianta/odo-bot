package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

public class ResourceParameterNode extends NavNode{

    private String name;

    public static ResourceParameterNode fromRecord(Record record){
        Node n = record.get(0).asNode();
        ResourceParameterNode result = fromRecord(record, new ResourceParameterNode());
        result.setName(n.get("name").asString());
        return result;
    }

    public String getName() {
        return name;
    }

    public ResourceParameterNode setName(String name) {
        this.name = name;
        return this;
    }
}
