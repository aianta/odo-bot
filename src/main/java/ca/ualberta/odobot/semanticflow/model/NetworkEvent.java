package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkEvent extends AbstractArtifact implements TimelineEntity{

    private static final Logger log = LoggerFactory.getLogger(NetworkEvent.class);


    @Override
    public int size() {
        return 1;
    }

    @Override
    public String symbol() {
        return "NET";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject();
    }

    @Override
    public long timestamp() {
        return getTimestamp().toInstant().toEpochMilli();
    }
}
