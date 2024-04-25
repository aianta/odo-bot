package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Set;

public class CollapsedEffectNode extends CollapsedNode{
    public CollapsedEffectNode(Set<Node> nodeSet) {
        super(nodeSet);
    }

    @Override
    public Node createNode(Transaction tx) {

        Node result = tx.createNode(Label.label(baseLabel), Label.label("CollapsedEffectNode"));
        result.setProperty("id", id.toString());
        result.setProperty("instances", instances.toArray(new String[1]));

        return result;
    }
}
