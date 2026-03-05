package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

public class GraphQLNode extends APINode{

    private String operationName;

    public static GraphQLNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        GraphQLNode result = fromRecord(record, new GraphQLNode());
        result.setOperationName(n.get("operationName").asString());
        result.setPath(n.get("path").asString());
        result.setMethod(n.get("method").asString());

        return result;
    }

    public GraphQLNode setOperationName(String operationName) {
        this.operationName = operationName;
        return this;
    }

    public String getOperationName() {
        return operationName;
    }
}
