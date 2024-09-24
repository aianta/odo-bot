package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.*;
import java.util.stream.Collectors;

public class ClickNode extends NavNode {


    private String xpath;

    private String text;

    public static ClickNode fromRecord(Record record){

        Node n = record.get(0).asNode();

        ClickNode result = fromRecord(record, new ClickNode());

        result.setXpath(n.get("xpath").asString());
        result.setText(n.get("text").asString());

        return result;

    }


    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }








}
