package ca.ualberta.odobot.telemetry.model;


import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@DataObject
public class TaskInstanceResults extends JsonObject {



    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    private String id;
    private String experimentId;
    private String taskId;
    private String instanceId;
    private String evaluationDatasetId;
    private String evaluationDatasetNotes;
    private int inputTokens;
    private int outputTokens;
    private int combinedTokens;
    private long duration;
    private String agent;
    private String agentVersion;
    private String result;
    private long timestampLong;
    private String timestamp;
    private JsonObject details;
    private String taskDescription;
    private String model;

    public TaskInstanceResults setModel(String model) {
        put("Model", model);
        return this;
    }

    public TaskInstanceResults setTaskDescription(String taskDescription) {
        put("TaskDescription", taskDescription);
        return this;
    }

    public TaskInstanceResults setDetails(JsonObject details) {
        put("Details", details);
        return this;
    }

    public TaskInstanceResults setId(String id) {
        put("Id", id);
        return this;
    }

    public TaskInstanceResults setTimestampLong(long timestampLong) {
        put("TimestampLong", timestampLong);
        return this;
    }

    public TaskInstanceResults setTimestamp(String timestamp) {
        put("Timestamp", timestamp);
        return this;
    }

    public TaskInstanceResults setExperimentId(String experimentId) {
        put("ExperimentId", experimentId);
        return this;
    }

    public TaskInstanceResults setTaskId(String taskId) {
        put("TaskId", taskId);
        return this;
    }

    public TaskInstanceResults setInstanceId(String instanceId) {
        put("InstanceId", instanceId);
        return this;
    }

    public TaskInstanceResults setEvaluationDatasetId(String evaluationDatasetId) {
        put("EvaluationDatasetId", evaluationDatasetId);
        return this;
    }

    public TaskInstanceResults setEvaluationDatasetNotes(String evaluationDatasetNotes) {
        put("EvaluationDatasetNotes", evaluationDatasetNotes);
        return this;
    }

    public TaskInstanceResults setInputTokens(int inputTokens) {
        put("InputTokens", inputTokens);
        return this;
    }

    public TaskInstanceResults setOutputTokens(int outputTokens) {
        put("OutputTokens", outputTokens);
        return this;
    }

    public TaskInstanceResults setCombinedTokens(int combinedTokens) {
        put("CombinedTokens", combinedTokens);
        return this;
    }

    public TaskInstanceResults setDuration(long duration) {
        put("Duration", duration);
        Duration _duration = Duration.ofMillis(duration);
        put("DurationString", "%dm%ds".formatted(_duration.toMinutes(), _duration.toSecondsPart()));
        return this;
    }

    public TaskInstanceResults setAgent(String agent) {
        put("Agent", agent);
        return this;
    }

    public TaskInstanceResults setAgentVersion(String agentVersion) {
        put("AgentVersion", agentVersion);
        return this;
    }

    public TaskInstanceResults setResult(String result) {
        put("Result", result);
        return this;
    }

    public TaskInstanceResults() {
        Instant now = Instant.now();
        Date date = Date.from(now);

        setTimestampLong(now.toEpochMilli());
        setTimestamp(sdf.format(date));
    }

    public TaskInstanceResults(JsonObject json) {
        this.mergeIn(json);
    }

    public JsonObject toJson() {
        return this;
    }
}
