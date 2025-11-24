package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The abstract artifact class collects together information extracted from a
 */
public abstract class AbstractArtifact {

    private static final Logger log = LoggerFactory.getLogger(AbstractArtifact.class);

    protected JsonObject semanticArtifacts = new JsonObject();

    protected ZonedDateTime timestamp;
    protected String xpath;
    protected Document domSnapshot;
    protected UUID id = UUID.randomUUID();
    private String htmlId; //HTML id if provided
    private String tag;
    private String baseURI; // The absolute base URL of the document containing the node:  https://developer.mozilla.org/en-US/docs/Web/API/Node/baseURI

    public JsonObject getSemanticArtifacts() {
        return semanticArtifacts;
    }

    public void setSemanticArtifacts(JsonObject semanticArtifacts) {
        this.semanticArtifacts = semanticArtifacts;
    }

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

        /**
         * Certain xpaths should be truncated. For example:
         * .../svg/g/path -> .../svg
         * .../button/i -> .../button
         * .../a/div/span/h1 -> .../a
         *
         */

        if (xpath.lastIndexOf("/a") != -1){
            this.xpath = xpath.substring(0, xpath.lastIndexOf("/a") + 2);
        } else if (xpath.lastIndexOf("/btn") != -1){
            this.xpath = xpath.substring(0, xpath.lastIndexOf("/btn") + 4);
        } else if (xpath.lastIndexOf("button") != -1){
            this.xpath = xpath.substring(0, xpath.lastIndexOf("button") + 6);
        } else if (xpath.lastIndexOf("svg") != -1){
            this.xpath = xpath.substring(0, xpath.lastIndexOf("svg") + 3);
        }else{
            this.xpath = xpath;
        }


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

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Element getTargetElement(){
        return domSnapshot.selectXpath(getXpath()).first();
    }
}
