package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import ca.ualberta.odobot.semanticflow.navmodel.BaseLabel;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.util.*;

public abstract class CollapsedNode {

    public Set<String> instances = new HashSet<>();
    protected String baseLabel;

    protected UUID id = UUID.randomUUID();

    public CollapsedNode(Set<Node> nodeSet){

        validate(nodeSet);

        nodeSet.forEach(node->{
            List<String> nodeInstances = Arrays.stream(((String[]) node.getProperty("instances"))).toList();
            instances.addAll(nodeInstances);
        });


    }

    public UUID id() {
        return id;
    }

    private void validate(Set<Node> nodeSet){
        if(nodeSet.isEmpty()){
            throw new RuntimeException("Cannot create collapsed node from empty node set!");
        }

        //Verify that all nodes in the set have the same base label
        String setLabel = BaseLabel.resolveBaseLabel(nodeSet.iterator().next());
        baseLabel = setLabel;

        if(!nodeSet.stream().allMatch(node->BaseLabel.resolveBaseLabel(node).equals(setLabel))){
            throw new RuntimeException("Cannot create collapsed node from node set with mismatching labels!");
        };

        //Verify that we're not merging APINodes
        if(setLabel.equals(BaseLabel.API_NODE.label)){
            throw new RuntimeException("Cannot collapse APINodes!");
        }
    }

    public Node createNode(Transaction tx){

        Node result = tx.createNode(Label.label(baseLabel));
        result.setProperty("id", id.toString());
        result.setProperty("instances", instances.toArray(new String[1]));

        return result;

    }


}
