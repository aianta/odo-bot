package ca.ualberta.odobot.semanticflow.model;

import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public abstract class AbstractArtifact {

    private static final Logger log = LoggerFactory.getLogger(AbstractArtifact.class);

    protected String xpath;
    protected Document domSnapshot;
    protected UUID id = UUID.randomUUID();
    private String htmlId; //HTML id if provided
    private String tag;
    private String baseURI; // The absolute base URL of the document containing the node:  https://developer.mozilla.org/en-US/docs/Web/API/Node/baseURI

    public String getHtmlId() {
        return htmlId;
    }

    public void setHtmlId(String htmlId) {
        this.htmlId = htmlId;
    }

    public String getXpath() {
        return xpath;
    }

    public void setXpath(String xpath) {
        this.xpath = xpath;
    }

    public Document getDomSnapshot() {
        return domSnapshot;
    }

    public void setDomSnapshot(Document domSnapshot) {
        this.domSnapshot = domSnapshot;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTag() {
        return tag;
    }

    public String getBaseURI() {
        return baseURI;
    }

    public void setBaseURI(String baseURI) {
        this.baseURI = baseURI;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}
