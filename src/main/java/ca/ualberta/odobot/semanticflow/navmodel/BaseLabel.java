package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

import java.util.Arrays;
import java.util.Iterator;

public enum BaseLabel{
    CLICK_NODE("ClickNode"),
    DATA_ENTRY_NODE("DataEntryNode"),
    EFFECT_NODE("EffectNode"),

    API_NODE("APINode");

    public String label;

    BaseLabel(String label){
        this.label = label;
    }

    /**
     * Resolves the {@link BaseLabel} associated with a node. This is necessary to support multi-label nodes.
     * @param n
     * @return the base label associated with this node.
     */
    public static String resolveBaseLabel(Node n){
        Iterator<Label> it = n.getLabels().iterator();

        while (it.hasNext()){
            Label label = it.next();

            BaseLabel matchingLabel = Arrays.stream(BaseLabel.values())
                    .filter(baseLabel->baseLabel.label.equals(label.name()))
                    .findAny()
                    .orElse(null);

            if(matchingLabel != null){
                return label.name();
            }
        }

        throw new RuntimeException("Node "+n.getElementId()+" did not have any known base label!");
    }
}