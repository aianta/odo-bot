package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskTokenUsageTest {

    @AfterEach
    void clearActive() {
        TokenUsageRecord.active = null;
    }

    @Test
    void reportWithoutActiveRecordIsANoOp() {
        TokenUsageRecord.active = null;
        assertDoesNotThrow(() -> TokenUsageRecord.report(LlmCallType.TASK_REWRITE, 10, 5, 15));
    }

    @Test
    void breaksUsageDownByCallTypeKindAndAgent() {
        AtomicReference<String> agent = new AtomicReference<>("ChartedAgent");
        TaskTokenUsage usage = new TaskTokenUsage(agent::get);
        TokenUsageRecord.active = usage;

        TokenUsageRecord.report(LlmCallType.TASK_REWRITE, 100, 20, 120);
        TokenUsageRecord.report(LlmCallType.SIMILAR_TASK_EMBEDDING, 30, 0, 30);
        TokenUsageRecord.report(LlmCallType.PATH_SELECTION, 200, 40, 240);
        agent.set("Qwen38Agent");
        TokenUsageRecord.report(LlmCallType.UNCHARTED_STEP, 1000, 50, 1050);
        TokenUsageRecord.report(LlmCallType.UNCHARTED_STEP, 1100, 60, 1160);

        JsonObject json = usage.toJson();
        assertEquals(2430, json.getInteger("inputTokens"));
        assertEquals(170, json.getInteger("outputTokens"));
        assertEquals(2600, json.getInteger("totalTokens"));
        assertEquals(5, json.getInteger("llmCalls"));

        JsonObject byKind = json.getJsonObject("byKind");
        assertEquals(1, byKind.getJsonObject("embedding").getInteger("llmCalls"));
        assertEquals(30, byKind.getJsonObject("embedding").getInteger("totalTokens"));
        assertEquals(4, byKind.getJsonObject("chat_completion").getInteger("llmCalls"));
        assertEquals(2570, byKind.getJsonObject("chat_completion").getInteger("totalTokens"));

        JsonObject byCallType = json.getJsonObject("byCallType");
        assertEquals(4, byCallType.size());
        assertEquals("embedding", byCallType.getJsonObject("similar-task-embedding").getString("kind"));
        assertEquals("chat_completion", byCallType.getJsonObject("task-rewrite").getString("kind"));
        assertEquals(2, byCallType.getJsonObject("uncharted-step").getInteger("llmCalls"));
        assertEquals(2210, byCallType.getJsonObject("uncharted-step").getInteger("totalTokens"));

        // Min, max and mean tokens of a single call of each type.
        JsonObject stepPerCall = byCallType.getJsonObject("uncharted-step").getJsonObject("perCall");
        assertEquals(1050, stepPerCall.getJsonObject("totalTokens").getInteger("min"));
        assertEquals(1160, stepPerCall.getJsonObject("totalTokens").getInteger("max"));
        assertEquals(1105.0, stepPerCall.getJsonObject("totalTokens").getDouble("mean"));
        assertEquals(1000, stepPerCall.getJsonObject("inputTokens").getInteger("min"));
        assertEquals(60, stepPerCall.getJsonObject("outputTokens").getInteger("max"));
        JsonObject chatPerCall = byKind.getJsonObject("chat_completion").getJsonObject("perCall");
        assertEquals(120, chatPerCall.getJsonObject("totalTokens").getInteger("min"));
        assertEquals(1160, chatPerCall.getJsonObject("totalTokens").getInteger("max"));
        assertEquals(2570.0 / 4, chatPerCall.getJsonObject("totalTokens").getDouble("mean"));

        JsonObject byAgent = json.getJsonObject("byAgent");
        JsonObject charted = byAgent.getJsonObject("ChartedAgent");
        assertEquals(3, charted.getInteger("llmCalls"));
        assertEquals(390, charted.getInteger("totalTokens"));
        assertFalse(charted.getJsonObject("byCallType").containsKey("uncharted-step"));
        JsonObject uncharted = byAgent.getJsonObject("Qwen38Agent");
        assertEquals(2, uncharted.getInteger("llmCalls"));
        assertEquals(java.util.Set.of("uncharted-step"), uncharted.getJsonObject("byCallType").fieldNames());
    }
}
