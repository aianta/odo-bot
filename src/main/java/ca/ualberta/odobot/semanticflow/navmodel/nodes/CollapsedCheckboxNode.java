package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CollapsedCheckboxNode extends CollapsedNode{

    public Set<String> xpaths = new HashSet<>();


    public CollapsedCheckboxNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node->{
            if(node.hasProperty("xpath")){
                String nodeXpath = (String) node.getProperty("xpath");
                xpaths.add(nodeXpath);
            }

            //Support for merging collapsed checkbox nodes recursively.
            if(node.hasProperty("xpaths")){
                Set<String> nodeXpaths = Arrays.stream((String []) node.getProperty("xpaths")).collect(Collectors.toSet());
                xpaths.addAll(nodeXpaths);
            }
        });
    }

    public Node createNode(Transaction tx){
        Node result = super.createNode(tx);
        result.addLabel(Label.label("CollapsedCheckboxNode"));
        result.setProperty("xpaths", xpaths.toArray(new String[1]));
        return result;
    }
}
