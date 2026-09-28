package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A snapshot of the page that OdoX sends as its first event after START_TRANSMISSION. It is the first
 * observation an agent receives.
 */
public class Observation implements TimelineEntity{

    private final JsonArray localContext;
    private final long timestamp;

    public Observation(JsonArray localContext, long timestamp){
        this.localContext = localContext;
        this.timestamp = timestamp;
    }

    /**
     * @return the events OdoX recorded locally before transmission started.
     */
    public JsonArray localContext(){
        return localContext;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "OBS";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject().put("localContext", localContext);
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
