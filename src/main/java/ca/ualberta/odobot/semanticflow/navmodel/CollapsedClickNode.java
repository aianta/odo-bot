package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.HashSet;
import java.util.Set;

public class CollapsedClickNode extends CollapsedNode{

    public Set<String> xpaths = new HashSet<>();
    public Set<String> texts = new HashSet<>();

    public CollapsedClickNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node->{
            String nodeXpath = (String) node.getProperty("xpath");
            String nodeText = (String) node.getProperty("text");
            xpaths.add(nodeXpath);
            texts.add(nodeText);
        });


    }

    @Override
    public Node createNode(Transaction tx) {


        Node result = tx.createNode(Label.label(baseLabel), Label.label("CollapsedClickNode"));
        result.setProperty("id", id.toString());
        result.setProperty("xpaths", xpaths.toArray(new String[1])); //To array with type
        result.setProperty("texts", texts.toArray(new String[1]));
        result.setProperty("instances", instances.toArray(new String[1]));

        return result;
    }
}
