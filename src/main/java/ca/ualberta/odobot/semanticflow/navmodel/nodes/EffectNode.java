package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.UUID;
import java.util.stream.Collectors;

public class EffectNode extends NavNode {

    public static EffectNode fromNode(Node n){
        EffectNode result = new EffectNode();
        result.setId(UUID.fromString(n.get("id").asString()));
        result.setInstances(n.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));

        return result;
    }

    public static EffectNode fromRecord(Record record){
        Node n = record.get(0).asNode();

       return fromNode(n);
    }

}
