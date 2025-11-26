package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

public class ClickNode extends XpathAndBasePathNode {


    private String text;

    public static ClickNode fromRecord(Record record){

        Node n = record.get(0).asNode();

        ClickNode result = fromRecord(record, new ClickNode());

        result.setText(n.get("text").asString());

        return result;

    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }








}
