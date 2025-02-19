package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

public class CheckboxNode extends NavNode{

    private String xpath;
    /**
     * Keeping a checkbox id seems reasonable for many cases, but as shown in: https://stackoverflow.com/questions/8537621/possible-to-associate-label-with-checkbox-without-using-for-id
     * Implicit association could technically be used to avoid using ids, and rather than deal with the complexities of only
     * some checkboxes having ids, I'm going to just use only the xpath for them.
     */
    //private String id;

    public static CheckboxNode fromRecord(Record record){
        Node n = record.get(0).asNode();

        CheckboxNode result = fromRecord(record, new CheckboxNode());
        result.setXpath(n.get("xpath").asString());

        return result;
    }

    public String getXpath() {
        return xpath;
    }

    public CheckboxNode setXpath(String xpath) {
        this.xpath = xpath;
        return this;
    }
}
