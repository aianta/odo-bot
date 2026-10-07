package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * The answer an agent reported when it completed a task that asks for information.
 */
public class TaskAnswer implements TimelineEntity{
    long timestamp = -1l;
    String answer;

    public TaskAnswer(String answer){
        this.answer = answer;
        timestamp = Instant.now().toEpochMilli();
    }

    public String getAnswer() {
        return answer;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String symbol() {
        return "ANS";
    }

    @Override
    public JsonObject toJson() {
        return new JsonObject().put("answer", answer);
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    public Screenshot getScreenshot(){
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public JsonObject getSemanticArtifacts() {
        return new JsonObject();
    }
}
