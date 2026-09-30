package ca.ualberta.odobot.guidance.feedback;

import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * OdoX could not find the element targeted by an instruction (UNRESOLVABLE_XPATH).
 */
public class UnresolvableXpath implements TimelineEntity {

    private final String xpath;
    private final String sourceNodeId;
    private final long timestamp;

    public UnresolvableXpath(String xpath, String sourceNodeId){
        this.xpath = xpath;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public String xpath() {
        return xpath;
    }

    public String sourceNodeId() {
        return sourceNodeId;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "UXP";
    }

    @Override
    public Screenshot getScreenshot() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("xpath", xpath)
                .put("sourceNodeId", sourceNodeId);
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public JsonObject getSemanticArtifacts() {
        return new JsonObject();
    }
}
