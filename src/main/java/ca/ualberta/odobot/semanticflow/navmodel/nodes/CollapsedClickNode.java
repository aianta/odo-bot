package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CollapsedClickNode extends CollapsedNode {

    public Set<String> xpaths = new HashSet<>();
    public Set<String> texts = new HashSet<>();

    public CollapsedClickNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node->{

            //Support for merging Collapsed ClickNodes recursively.
            if(node.hasProperty("xpaths")){
                Set<String> nodeXpaths = Arrays.stream((String []) node.getProperty("xpaths")).collect(Collectors.toSet());
                xpaths.addAll(nodeXpaths);
            }

            if(node.hasProperty("texts")){
                Set<String> nodeTexts = Arrays.stream((String []) node.getProperty("texts")).collect(Collectors.toSet());
                texts.addAll(nodeTexts);
            }

            if(node.hasProperty("xpath")){
                String nodeXpath = (String) node.getProperty("xpath");
                xpaths.add(nodeXpath);
            }

            if(node.hasProperty("text")){
                String nodeText = (String) node.getProperty("text");
                texts.add(nodeText);
            }


        });


    }

    @Override
    public Node createNode(Transaction tx) {

        Node result = super.createNode(tx);
        result.addLabel(Label.label("CollapsedClickNode"));
        result.setProperty("xpaths", xpaths.toArray(new String[1])); //To array with type
        result.setProperty("texts", texts.toArray(new String[1]));


        return result;
    }
}
