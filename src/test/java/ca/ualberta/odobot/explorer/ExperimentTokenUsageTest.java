package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.common.LlmCallType;
import ca.ualberta.odobot.guidance.TaskTokenUsage;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        TaskTokenUsage usage = new TaskTokenUsage(() -> "ChartedAgent");
        for (Call call : calls) {
            usage.record(call.type(), call.input(), call.output(), call.input() + call.output());
        }
        JsonObject task = new JsonObject()
                .put("evalId", evalId)
                .put("mode", "CHARTED")
                .put("outcome", "completed")
                .mergeIn(usage.toJson());
        Files.writeString(experimentFolder.resolve(evalId + "-tokens.json"), task.encodePrettily());
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

        JsonObject summary = ExperimentTokenUsage.summarizeAndSave("exp", experimentFolder.toString(), results.toString());

        JsonObject saved = new JsonObject(Files.readString(results.resolve("exp-tokens.json")));
        assertEquals(summary, saved);
        assertEquals("exp", saved.getString("experimentId"));
        assertEquals(1, saved.getInteger("taskCount"));
    }
}
