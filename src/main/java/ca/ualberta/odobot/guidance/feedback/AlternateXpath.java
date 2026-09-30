package ca.ualberta.odobot.guidance.feedback;

import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * OdoX executed an instruction on a different element than the xpath it was sent (REGISTER_ALTERNATE_XPATH).
 */
public class AlternateXpath implements TimelineEntity {

    private final String alternateXpath;
    private final String sourceNodeId;
    private final long timestamp;

    public AlternateXpath(String alternateXpath, String sourceNodeId){
        this.alternateXpath = alternateXpath;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public String alternateXpath() {
        return alternateXpath;
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
        return "AXP";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject()
                .put("alternateXpath", alternateXpath)
                .put("sourceNodeId", sourceNodeId);
    }

    public Screenshot getScreenshot(){
        throw new UnsupportedOperationException("Not supported yet.");
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
