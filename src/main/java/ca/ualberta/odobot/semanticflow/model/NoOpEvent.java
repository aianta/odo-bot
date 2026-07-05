package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

public class NoOpEvent implements TimelineEntity{
    long timestamp = -1l;
    public NoOpEvent(){
        timestamp = Instant.now().toEpochMilli();
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "NOP";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject();
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
