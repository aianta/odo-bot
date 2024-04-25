package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.HashSet;
import java.util.Set;

public class CollapsedDataEntryNode extends CollapsedNode{

    public Set<String> xpaths = new HashSet<>();

    public CollapsedDataEntryNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node->{
            String nodeXpath = (String) node.getProperty("xpath");
            xpaths.add(nodeXpath);
        });
    }

    @Override
    public Node createNode(Transaction tx) {

        Node result = tx.createNode(Label.label(baseLabel), Label.label("CollapsedDataEntryNode"));
        result.setProperty("id", id.toString());
        result.setProperty("xpaths", xpaths.toArray(new String[1]));
        result.setProperty("instances", instances.toArray(new String[1]));

        return result;
    }
}
