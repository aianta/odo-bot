package ca.ualberta.odobot.task;

import ca.ualberta.odobot.guidance.ExecutionMode;
import ca.ualberta.odobot.guidance.TaskTiming;
import ca.ualberta.odobot.guidance.TaskTokenUsage;
import ca.ualberta.odobot.telemetry.model.TaskInstanceResults;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scores a finished task with the evaluation script against the experiment's ground-truth dataset, and builds its
 * telemetry. Runs the script as a python subprocess, so it blocks: call it on a worker thread.
 */
public class TaskScorer {

    private static final Logger log = LoggerFactory.getLogger(TaskScorer.class);

    private final JsonObject explorerConfig;
    private final JsonObject experimentRequest;

    /**
     * @param explorerConfig config/explorer.yaml, which names the evaluation script.
     * @param experimentRequest the evaluate request, which names the dataset.
     */
    public TaskScorer(JsonObject explorerConfig, JsonObject experimentRequest){
        this.explorerConfig = explorerConfig;
        this.experimentRequest = experimentRequest;
    }

    /**
     * @return true if the experiment has a ground-truth dataset to score against.
     */
    public boolean enabled(){
        return experimentRequest.containsKey("evaluationDatasetPath");
    }

    /**
     * Score the task from its saved event log, and fill in its telemetry.
     *
     * @param eventsFile the task's raw event log.
     * @param taskQueryConstructionFile the charted agent's task query construction result, which the script checks.
     * @param resultFile where the script writes the task's result.
     * @return the task's telemetry. Its result is MISSING EVENTS if the event log wasn't saved.
     */
    public TaskInstanceResults score(JsonObject task, ExecutionMode mode, String eventsFile, String taskQueryConstructionFile,
                                     String resultFile, TaskTiming timing) throws IOException, InterruptedException {
        String evalScriptPath = "%s/%s".formatted(explorerConfig.getString("evaluationScriptsPath"), explorerConfig.getString("evaluationScript"));
        String datasetPath = datasetPath();

        TaskInstanceResults telemetry = new TaskInstanceResults();

        if (Files.exists(Path.of(eventsFile))){
            List<String> command = new ArrayList<>(List.of(
                    "python",
                    "-X", "utf8",
                    evalScriptPath,
                    "-t", datasetPath,
                    "-o", resultFile
            ));
            if(mode == ExecutionMode.CHARTED){
                command.addAll(List.of(
                        "--single-odobot-execution-events",
                        eventsFile,
                        "--single-odobot-task-query-construction",
                        taskQueryConstructionFile
                ));
            }else{
                //Only the charted agent constructs a task query, so there is no target to check.
                command.addAll(List.of(
                        "--single-odobot-uncharted-execution-events",
                        eventsFile
                ));
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            log.info("{}", String.join(" ", pb.command()));

            pb.inheritIO();
            timing.markScoringStart();
            Process evalProcess = pb.start();
            evalProcess.waitFor();
            timing.markScoringEnd();

            JsonObject taskResult = new JsonObject(Buffer.buffer(Files.readAllBytes(Path.of(resultFile))));
            telemetry.setDetails(taskResult.getJsonArray("details").getJsonObject(0));
            telemetry.setResult(taskResult.getInteger("correct") == 1?"PASS":"FAIL");
            telemetry.setNumNetworkRequests(taskResult.getInteger("num_network_requests"));
        }else{
            telemetry.setResult("MISSING EVENTS");
        }

        String parentTaskId = parentTaskId(datasetPath, task.getString("id"));
        if(parentTaskId != null){
            telemetry.setTaskId(parentTaskId);
        }
        return telemetry;
    }

    /**
     * Fill in the parts of a task's telemetry that don't come from scoring.
     */
    public void describe(TaskInstanceResults telemetry, JsonObject task, String experimentId, String model,
                         TaskTiming timing, TaskTokenUsage tokenUsage){
        telemetry.setExperimentId(experimentId);
        telemetry.setInstanceId(task.getString("id"));
        telemetry.setAgent(explorerConfig.getString("agentName"));
        telemetry.setAgentVersion(explorerConfig.getString("agentVersion"));
        telemetry.setTaskDescription(task.getString("task"));
        telemetry.setEvaluationDatasetId(datasetPath());
        //Duration keeps its meaning from before the phases were timed: the execution of the task.
        telemetry.setDuration(timing.executionMs());
        telemetry.setSetupDuration(timing.setupMs());
        telemetry.setExecutionDuration(timing.executionMs());
        telemetry.setArtifactsDuration(timing.artifactsMs());
        telemetry.setScoringDuration(timing.scoringMs());
        telemetry.setTotalDuration(timing.totalMs());
        telemetry.setBeforeSetupTime(timing.beforeSetup().toString());
        telemetry.setAfterScoringTime(timing.afterScoring().toString());
        if(tokenUsage != null){
            telemetry.setInputTokens(tokenUsage.inputTokens);
            telemetry.setOutputTokens(tokenUsage.outputTokens);
            telemetry.setCombinedTokens(tokenUsage.totalTokens);
            telemetry.setLlmCalls(tokenUsage.llmCalls);
            telemetry.setInferenceDuration(timing.inferenceMs(tokenUsage.inferenceMs()));
            telemetry.setOtherExecutionDuration(timing.otherExecutionMs(tokenUsage.inferenceMs()));
            telemetry.setFailedLlmAttempts(tokenUsage.failedAttempts);
        }
        telemetry.setModel(model);
    }

    private String datasetPath(){
        return "%s/%s".formatted(explorerConfig.getString("evaluationScriptsPath"), experimentRequest.getString("evaluationDatasetPath"));
    }

    private static String parentTaskId(String datasetPath, String instanceId){
        try{
            JsonArray dataset = new JsonArray(Buffer.buffer(Files.readAllBytes(Path.of(datasetPath))));
            JsonObject parentTask = dataset.stream()
                    .map(JsonObject.class::cast)
                    .filter(taskDefinition->taskDefinition.getJsonArray("instances")
                            .stream()
                            .map(JsonObject.class::cast)
                            .map(instance->instance.getString("id"))
                            .anyMatch(instanceId::equals))
                    .findFirst().get();

            return parentTask.getString("id");
        }catch ( Exception e){
            log.error(e.getMessage(), e);
            return null;
        }
    }
}
