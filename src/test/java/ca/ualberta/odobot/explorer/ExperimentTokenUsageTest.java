package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.common.LlmCallType;
import ca.ualberta.odobot.guidance.TaskTokenUsage;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static ca.ualberta.odobot.common.LlmCallType.*;
import static org.junit.jupiter.api.Assertions.*;

class ExperimentTokenUsageTest {

    @TempDir
    Path experimentFolder;

    private record Call(LlmCallType type, int input, int output) {
    }

    /**
     * Writes a task file the way the RequestManager does: task metadata merged with a {@link TaskTokenUsage}.
     */
    private void writeTask(String evalId, Call... calls) throws IOException {
        writeTask(evalId, "completed", null, calls);
    }

    /**
     * @param timing the task's {@code timing} object, or null for a task file from before timing was recorded.
     */
    private void writeTask(String evalId, String outcome, JsonObject timing, Call... calls) throws IOException {
        TaskTokenUsage usage = new TaskTokenUsage(() -> "ChartedAgent");
        for (Call call : calls) {
            usage.record(call.type(), call.input(), call.output(), call.input() + call.output());
        }
        JsonObject task = new JsonObject()
                .put("evalId", evalId)
                .put("mode", "CHARTED")
                .put("outcome", outcome)
                .mergeIn(usage.toJson());
        if (timing != null) {
            task.put("timing", timing);
        }
        Files.writeString(experimentFolder.resolve(evalId + "-tokens.json"), task.encodePrettily());
    }

    private static JsonObject timing(long setupMs, long executionMs, Long scoringMs, long totalMs) {
        JsonObject timing = new JsonObject()
                .put("setupMs", setupMs)
                .put("executionMs", executionMs)
                .put("artifactsMs", 10L)
                .put("totalMs", totalMs);
        if (scoringMs != null) {
            timing.put("scoringMs", scoringMs);
        }
        return timing;
    }

    private void writeThreeTimedTasks() throws IOException {
        writeTask("task-a", "completed", timing(5000, 60000, 2000L, 67010), new Call(TASK_REWRITE, 70, 20));
        writeTask("task-b", "failed: Timeout!", timing(7000, 180000, 3000L, 190010), new Call(TASK_REWRITE, 100, 10));
        // Not scored, e.g. its events file was missing.
        writeTask("task-c", "completed", timing(6000, 30000, null, 36010), new Call(TASK_REWRITE, 200, 30));
    }

    private void writeThreeTasks() throws IOException {
        writeTask("task-a",
                new Call(TASK_REWRITE, 70, 20),
                new Call(SIMILAR_TASK_EMBEDDING, 30, 0));
        writeTask("task-b",
                new Call(TASK_REWRITE, 100, 10),
                new Call(PATH_SELECTION, 270, 40),
                new Call(PATH_SELECTION, 150, 20),
                new Call(SIMILAR_TASK_EMBEDDING, 30, 0));
        writeTask("task-c",
                new Call(TASK_REWRITE, 200, 30));
    }

    private static void assertStats(JsonObject stats, long min, long max, double mean) {
        assertEquals(min, stats.getLong("min"), "min");
        assertEquals(max, stats.getLong("max"), "max");
        assertEquals(mean, stats.getDouble("mean"), 1e-9, "mean");
    }

    @Test
    void summarizesEveryTaskFileInTheFolder() throws IOException {
        writeThreeTasks();

        // Files the summary must ignore: other experiment artifacts, and the summary itself in the results folder.
        Files.writeString(experimentFolder.resolve("task-a.json"), "[]");
        Files.writeString(experimentFolder.resolve("task-a-history-tokens.json"), new JsonObject().put("foo", 1).encode());
        Files.createDirectories(experimentFolder.resolve("results"));
        Files.writeString(experimentFolder.resolve("results/exp-tokens.json"), new JsonObject().put("evalId", "x").put("totalTokens", 999).encode());

        JsonObject summary = ExperimentTokenUsage.summarize(experimentFolder.toString());

        assertEquals(3, summary.getInteger("taskCount"));
        assertStats(summary.getJsonObject("totalTokens"), 120, 620, 970.0 / 3);
        assertEquals(970, summary.getJsonObject("totalTokens").getLong("sum"));
        assertStats(summary.getJsonObject("llmCalls"), 1, 4, 7.0 / 3);
        assertEquals(7, summary.getJsonObject("llmCalls").getLong("sum"));
        assertEquals(850, summary.getJsonObject("inputTokens").getLong("sum"));
        assertEquals(120, summary.getJsonObject("outputTokens").getLong("sum"));
        assertEquals("task-a", summary.getString("minTotalTokensEvalId"));
        assertEquals("task-b", summary.getString("maxTotalTokensEvalId"));
        assertEquals(3, summary.getJsonArray("tasks").size());
    }

    @Test
    void givesPerCallAndPerTaskStatsForEachCallType() throws IOException {
        writeThreeTasks();

        JsonObject byCallType = ExperimentTokenUsage.summarize(experimentFolder.toString()).getJsonObject("byCallType");

        // Every task rewrote its task once.
        JsonObject rewrite = byCallType.getJsonObject("task-rewrite");
        assertEquals("chat_completion", rewrite.getString("kind"));
        assertEquals(3, rewrite.getLong("llmCalls"));
        assertEquals(430, rewrite.getLong("totalTokens"));
        assertStats(rewrite.getJsonObject("perCall").getJsonObject("totalTokens"), 90, 230, 430.0 / 3);
        assertStats(rewrite.getJsonObject("perCall").getJsonObject("inputTokens"), 70, 200, 370.0 / 3);
        assertStats(rewrite.getJsonObject("perTask").getJsonObject("llmCalls"), 1, 1, 1.0);
        assertStats(rewrite.getJsonObject("perTask").getJsonObject("totalTokens"), 90, 230, 430.0 / 3);

        // Only task-b selected a path (twice); the other tasks count as 0 per task, but not per call.
        JsonObject pathSelection = byCallType.getJsonObject("path-selection");
        assertEquals(2, pathSelection.getLong("llmCalls"));
        assertStats(pathSelection.getJsonObject("perCall").getJsonObject("totalTokens"), 170, 310, 240.0);
        assertStats(pathSelection.getJsonObject("perCall").getJsonObject("outputTokens"), 20, 40, 30.0);
        assertStats(pathSelection.getJsonObject("perTask").getJsonObject("llmCalls"), 0, 2, 2.0 / 3);
        assertStats(pathSelection.getJsonObject("perTask").getJsonObject("totalTokens"), 0, 480, 160.0);

        JsonObject embedding = byCallType.getJsonObject("similar-task-embedding");
        assertEquals("embedding", embedding.getString("kind"));
        assertStats(embedding.getJsonObject("perCall").getJsonObject("outputTokens"), 0, 0, 0.0);
    }

    @Test
    void givesPerCallAndPerTaskStatsForEachKind() throws IOException {
        writeThreeTasks();

        JsonObject byKind = ExperimentTokenUsage.summarize(experimentFolder.toString()).getJsonObject("byKind");

        JsonObject chat = byKind.getJsonObject("chat_completion");
        assertEquals(5, chat.getLong("llmCalls"));
        assertEquals(910, chat.getLong("totalTokens"));
        assertStats(chat.getJsonObject("perCall").getJsonObject("totalTokens"), 90, 310, 182.0);
        assertStats(chat.getJsonObject("perTask").getJsonObject("totalTokens"), 90, 590, 910.0 / 3);

        JsonObject embedding = byKind.getJsonObject("embedding");
        assertEquals(2, embedding.getLong("llmCalls"));
        assertStats(embedding.getJsonObject("perTask").getJsonObject("llmCalls"), 0, 1, 2.0 / 3);
    }

    @Test
    void emptyFolderGivesZeroTasks() {
        JsonObject summary = ExperimentTokenUsage.summarize(experimentFolder.toString());
        assertEquals(0, summary.getInteger("taskCount"));
        assertEquals(0, summary.getJsonObject("totalTokens").getLong("sum"));
        assertEquals(0.0, summary.getJsonObject("totalTokens").getDouble("mean"));
        assertTrue(summary.getJsonObject("byCallType").isEmpty());
    }

    @Test
    void savesTheSummaryInTheResultsFolder() throws IOException {
        writeTask("task-a", new Call(TASK_REWRITE, 100, 20));
        Path results = experimentFolder.resolve("results");

        JsonObject summary = ExperimentTokenUsage.summarizeAndSave("exp", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 0,
                experimentFolder.toString(), results.toString());

        JsonObject saved = new JsonObject(Files.readString(results.resolve("exp-tokens.json")));
        assertEquals(summary, saved);
        assertEquals("exp", saved.getString("experimentId"));
        assertEquals(1, saved.getInteger("taskCount"));
    }

    @Test
    void givesStatsForEachTimedPhase() throws IOException {
        writeThreeTimedTasks();

        JsonObject timing = ExperimentTokenUsage.summarize(experimentFolder.toString()).getJsonObject("timing");

        assertEquals(3, timing.getInteger("taskCount"));
        assertStats(timing.getJsonObject("setupMs"), 5000, 7000, 6000.0);
        assertStats(timing.getJsonObject("executionMs"), 30000, 180000, 90000.0);
        assertEquals(270000, timing.getJsonObject("executionMs").getLong("sum"));
        assertStats(timing.getJsonObject("totalMs"), 36010, 190010, 293030.0 / 3);
        // Only the tasks that were scored count toward scoring.
        assertStats(timing.getJsonObject("scoringMs"), 2000, 3000, 2500.0);
        assertEquals("task-c", timing.getString("fastestEvalId"));
        assertEquals("task-b", timing.getString("slowestEvalId"));
    }

    @Test
    void groupsTaskTimingByOutcome() throws IOException {
        writeThreeTimedTasks();

        JsonObject byOutcome = ExperimentTokenUsage.summarize(experimentFolder.toString())
                .getJsonObject("timing").getJsonObject("byOutcome");

        assertEquals(2, byOutcome.getJsonObject("completed").getInteger("taskCount"));
        assertStats(byOutcome.getJsonObject("completed").getJsonObject("executionMs"), 30000, 60000, 45000.0);
        assertEquals(1, byOutcome.getJsonObject("failed").getInteger("taskCount"));
        assertStats(byOutcome.getJsonObject("failed").getJsonObject("executionMs"), 180000, 180000, 180000.0);
    }

    @Test
    void tasksWithoutTimingStillCountTowardTokens() throws IOException {
        writeThreeTimedTasks();
        writeTask("task-d", new Call(TASK_REWRITE, 50, 50));

        JsonObject summary = ExperimentTokenUsage.summarize(experimentFolder.toString());

        assertEquals(4, summary.getInteger("taskCount"));
        assertEquals(3, summary.getJsonObject("timing").getInteger("taskCount"));
        assertStats(summary.getJsonObject("timing").getJsonObject("executionMs"), 30000, 180000, 90000.0);

        JsonObject taskA = summary.getJsonArray("tasks").getJsonObject(0);
        assertEquals("task-a", taskA.getString("evalId"));
        assertEquals(60000, taskA.getLong("executionMs"));
        assertEquals(67010, taskA.getLong("totalMs"));
        JsonObject taskD = summary.getJsonArray("tasks").getJsonObject(3);
        assertFalse(taskD.containsKey("executionMs"));
    }

    @Test
    void emptyFolderGivesZeroTimedTasks() {
        JsonObject timing = ExperimentTokenUsage.summarize(experimentFolder.toString()).getJsonObject("timing");
        assertEquals(0, timing.getInteger("taskCount"));
        assertEquals(0.0, timing.getJsonObject("executionMs").getDouble("mean"));
        assertFalse(timing.containsKey("slowestEvalId"));
        assertTrue(timing.getJsonObject("byOutcome").isEmpty());
    }

    private static JsonObject withInference(JsonObject timing, long inferenceMs) {
        return timing.put("inferenceMs", inferenceMs).put("otherExecutionMs", timing.getLong("executionMs") - inferenceMs);
    }

    @Test
    void splitsExecutionIntoInferenceAndOtherWork() throws IOException {
        writeTask("task-a", "completed", withInference(timing(5000, 60000, 2000L, 67010), 45000), new Call(UNCHARTED_STEP, 70, 20));
        writeTask("task-b", "failed: Timeout!", withInference(timing(7000, 180000, 3000L, 190010), 90000), new Call(UNCHARTED_STEP, 100, 10));
        // From before inference was timed: counts toward execution, but not toward the split.
        writeTask("task-c", "completed", timing(6000, 30000, null, 36010), new Call(UNCHARTED_STEP, 200, 30));

        JsonObject summary = ExperimentTokenUsage.summarize(experimentFolder.toString());
        JsonObject timing = summary.getJsonObject("timing");

        assertStats(timing.getJsonObject("inferenceMs"), 45000, 90000, 67500.0);
        assertEquals(135000, timing.getJsonObject("inferenceMs").getLong("sum"));
        assertStats(timing.getJsonObject("otherExecutionMs"), 15000, 90000, 52500.0);
        assertEquals(135000.0 / 240000, timing.getDouble("inferenceShare"), 1e-9);
        assertStats(timing.getJsonObject("byOutcome").getJsonObject("failed").getJsonObject("inferenceMs"), 90000, 90000, 90000.0);
        assertEquals(45000, summary.getJsonArray("tasks").getJsonObject(0).getLong("inferenceMs"));
        assertFalse(summary.getJsonArray("tasks").getJsonObject(2).containsKey("inferenceMs"));
    }

    @Test
    void noInferenceSplitGivesZeroShare() throws IOException {
        writeThreeTimedTasks();
        JsonObject timing = ExperimentTokenUsage.summarize(experimentFolder.toString()).getJsonObject("timing");
        assertEquals(0.0, timing.getDouble("inferenceShare"));
        assertEquals(0, timing.getJsonObject("inferenceMs").getLong("sum"));
    }

    @Test
    void savesTheExperimentWallClock() throws IOException {
        writeThreeTimedTasks();
        Path results = experimentFolder.resolve("results");
        Instant start = Instant.parse("2026-10-07T12:00:00Z");

        ExperimentTokenUsage.summarizeAndSave("exp", start, start.plusSeconds(300), 2, experimentFolder.toString(), results.toString());

        JsonObject timing = new JsonObject(Files.readString(results.resolve("exp-tokens.json"))).getJsonObject("timing");
        assertEquals("2026-10-07T12:00:00Z", timing.getString("experimentStart"));
        assertEquals("2026-10-07T12:05:00Z", timing.getString("experimentEnd"));
        assertEquals(300000, timing.getLong("wallClockMs"));
        assertEquals(2, timing.getInteger("skippedTasks"));
    }
}
