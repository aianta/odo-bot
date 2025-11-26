package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Set;

public class CollapsedDataEntryNode extends CollapsedXpathAndBasePathNode {


    public CollapsedDataEntryNode(Set<Node> nodeSet) {
        super(nodeSet);


    }

    @Override
    public Node createNode(Transaction tx) {

        Node result = super.createNode(tx);
        result.addLabel(Label.label("CollapsedDataEntryNode"));

        return result;
    }
}
