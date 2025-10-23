package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;


public class DataEntryNode extends NavNode {

    private String xpath;
    private String editorId;

    public static DataEntryNode fromRecord(Record record){

        Node n  = record.get(0).asNode();

        DataEntryNode result = fromRecord(record, new DataEntryNode());
        result.setXpath(n.get("xpath").asString());

        String editorId = n.get("editorId").asString();
        if (editorId != null && !editorId.equals("null")) {
            result.setEditorId(editorId);
        }


        return  result;
    }


    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }

    public String getEditorId() {
        return editorId;
    }

    public DataEntryNode setEditorId(String editorId) {
        this.editorId = editorId;
        return this;
    }
}
