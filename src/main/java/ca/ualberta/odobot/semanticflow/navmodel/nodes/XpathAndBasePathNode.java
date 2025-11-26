package ca.ualberta.odobot.semanticflow.navmodel.nodes;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

public class XpathAndBasePathNode extends NavNode{

    private String xpath;
    private String basePath;

    public static <T extends XpathAndBasePathNode> T fromRecord(Record record, T out){
        Node n = record.get(0).asNode();

        T result = NavNode.fromRecord(record, out);

        result.setXpath(n.get("xpath").asString());
        result.setBasePath(n.get("basePath").asString());

        return result;
    }

    public String getXpath() {
        return xpath;
    }

    public XpathAndBasePathNode setXpath(String xpath) {
        this.xpath = xpath;
        return this;
    }

    public String getBasePath() {
        return basePath;
    }

    public XpathAndBasePathNode setBasePath(String basePath) {
        this.basePath = basePath;
        return this;
    }
}
