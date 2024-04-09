package ca.ualberta.odobot.semanticflow.navmodel;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.*;
import java.util.stream.Collectors;

public class ClickNode extends NavNode{


    private String xpath;

    private String text;

    public static ClickNode fromRecord(Record record){

        Node n = record.get(0).asNode();

        ClickNode result = new ClickNode();
        result.setId(UUID.fromString(n.get("id").asString()));
        result.setXpath(n.get("xpath").asString());
        result.setText(n.get("text").asString());
        result.setInstances(n.get("instances").asList().stream().map(o->(String)o).collect(Collectors.toSet()));
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
