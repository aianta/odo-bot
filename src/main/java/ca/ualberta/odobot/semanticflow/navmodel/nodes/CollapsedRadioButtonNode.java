package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CollapsedRadioButtonNode extends CollapsedXpathAndBasePathNode {

    public Set<String> radioGroups = new HashSet<>();
    public Set<String> relatedElements = new HashSet<>();


    public CollapsedRadioButtonNode(Set<Node> nodeSet) {
        super(nodeSet);

        nodeSet.forEach(node -> {

            if(node.hasProperty("radioGroup")){
                String radioGroup = (String)node.getProperty("radioGroup");
                radioGroups.add(radioGroup);
            }

            //Handle case where we're merging collapsed radiobutton nodes together.
            if(node.hasProperty("radioGroups")){
                Set<String> _radioGroups = Arrays.stream((String[])node.getProperty("radioGroups")).collect(Collectors.toSet());
                radioGroups.addAll(_radioGroups);
            }

            if(node.hasProperty("relatedElements")){
                relatedElements.addAll(Arrays.stream((String[])node.getProperty("relatedElements")).collect(Collectors.toSet()));
            }
        });
    }

    public Node createNode(Transaction tx){
        Node result = super.createNode(tx);
        result.addLabel(Label.label("CollapsedRadioButtonNode"));
        result.setProperty("radioGroups", radioGroups.toArray(new String[1]));
        result.setProperty("relatedElements", relatedElements.toArray(new String[1]));
        return result;
    }
}
