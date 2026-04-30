package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CollapsedSelectOptionNode extends CollapsedXpathAndBasePathNode {

    public Set<String> optionSets = new HashSet<>();

    public CollapsedSelectOptionNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node -> {

            if(node.hasProperty("options")){
                optionSets.addAll(Arrays.stream((String[])node.getProperty("options")).collect(Collectors.toSet()));
            }


        });
    }

    public Node createNode(Transaction tx) {
        Node result = super.createNode(tx);
        result.addLabel(Label.label("CollapsedSelectOptionNode"));
        result.setProperty("options", optionSets.toArray(new String[1]));
        return result;
    }
}
