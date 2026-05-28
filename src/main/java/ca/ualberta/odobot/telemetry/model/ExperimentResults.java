package ca.ualberta.odobot.telemetry.model;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@DataObject
public class ExperimentResults extends JsonObject {



    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    private String id;
    private String experimentId;
    private int successfulTasks;
    private int failedTasks;
    private int submittedTasks;
    private int evaluatedTasks;
    private int numTasks;
    private String notes;
    private String agent;
    private String agentVersion;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCombinedTokens;
    private long duration;
    private String model;
    private String rawDataPath;
    private String evaluationDatasetId;
    private String evaluationDatasetNotes;
    private long timestampLong;
    private String timestamp;

    public ExperimentResults setSubmittedTasks(int submittedTasks) {
        put("SubmittedTasks", submittedTasks);
        return this;
    }

    public ExperimentResults setEvaluatedTasks(int evaluatedTasks) {
        put("EvaluatedTasks", evaluatedTasks);
        return this;
    }

    public ExperimentResults setId(String id) {
        put("Id", id);
        return this;
    }

    public ExperimentResults setTimestampLong(long timestampLong) {
        put("TimestampLong", timestampLong);
        return this;
    }

    public ExperimentResults setTimestamp(String timestamp) {
        put("Timestamp", timestamp);
        return this;
    }

    public ExperimentResults setExperimentId(String experimentId) {
        put("ExperimentId", experimentId);
        return this;
    }

    public  ExperimentResults setSuccessfulTasks(int successfulTasks) {
        put("SuccessfulTasks", successfulTasks);
        return this;
    }

    public   ExperimentResults setFailedTasks(int failedTasks) {
        put("FailedTasks", failedTasks);
        return this;
    }

    public ExperimentResults setNumTasks(int numTasks) {
        put("NumTasks", numTasks);
        return this;
    }

    public ExperimentResults setNotes(String notes) {
        put("Notes", notes);
        return this;
    }

    public ExperimentResults setAgent(String agent) {
        put("Agent", agent);
        return this;
    }

    public ExperimentResults setAgentVersion(String agentVersion) {
        put("AgentVersion", agentVersion);
        return this;
    }

    public ExperimentResults setTotalInputTokens(int totalInputTokens) {
        put("TotalInputTokens", totalInputTokens);
        return this;
    }

    public ExperimentResults setTotalOutputTokens(int totalOutputTokens) {
        put("TotalOutputTokens", totalOutputTokens);
        return this;
    }

    public ExperimentResults setTotalCombinedTokens(int totalCombinedTokens) {
        put("TotalCombinedTokens", totalCombinedTokens);
        return this;
    }

    public ExperimentResults setDuration(long duration) {
        put("Duration Milliseconds", duration);
        Duration _duration = Duration.ofMillis(duration);
        put("Duration", "%dh%dm%ds".formatted(_duration.toHours(), _duration.toMinutesPart(), _duration.toSecondsPart()));
        return this;
    }

    public ExperimentResults setModel(String model) {
        put("Model", model);
        return this;
    }

    public ExperimentResults setRawDataPath(String rawDataPath) {
        put("RawDataPath", rawDataPath);
        return this;
    }

    public ExperimentResults setEvaluationDatasetId(String evaluationDatasetId) {
        put("EvaluationDatasetId", evaluationDatasetId);
        return this;
    }

    public ExperimentResults setEvaluationDatasetNotes(String evaluationDatasetNotes) {
        put("EvaluationDatasetNotes", evaluationDatasetNotes);
        return this;
    }


    public ExperimentResults() {
        Instant now = Instant.now();
        Date date = Date.from(now);

        setTimestampLong(now.toEpochMilli());
        setTimestamp(sdf.format(date));
    }

    public ExperimentResults(JsonObject json){
        this.mergeIn(json);
    }

    public JsonObject toJson() {
        return this;
    }
}
