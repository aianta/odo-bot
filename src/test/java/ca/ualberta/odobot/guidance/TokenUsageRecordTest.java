package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageRecordTest {

    @AfterEach
    void clearActive() {
        TokenUsageRecord.active = null;
    }

    @Test
    void spanWithoutActiveRecordIsANoOp() {
        TokenUsageRecord.InferenceSpan span = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        assertDoesNotThrow(() -> {
            span.attemptFailed();
            span.end();
            span.fail();
        });
    }

    @Test
    void sequentialCallsAddUp() throws InterruptedException {
        TokenUsageRecord record = new TokenUsageRecord();
        TokenUsageRecord.active = record;

        TokenUsageRecord.InferenceSpan first = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        Thread.sleep(30);
        first.end();
        Thread.sleep(50); // Not inference.
        TokenUsageRecord.InferenceSpan second = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        Thread.sleep(30);
        second.end();

        long inference = record.inferenceMs();
        assertTrue(inference >= 60 && inference < 80, "inference " + inference);
        long stepMs = record.toJson().getJsonObject("byCallType").getJsonObject("uncharted-step").getLong("inferenceMs");
        assertTrue(Math.abs(inference - stepMs) <= 1, "union " + inference + " vs call type " + stepMs);
    }

    @Test
    void overlappingCallsCountTheirUnion() throws InterruptedException {
        TokenUsageRecord record = new TokenUsageRecord();
        TokenUsageRecord.active = record;

        TokenUsageRecord.InferenceSpan chat = TokenUsageRecord.begin(LlmCallType.PATH_SELECTION);
        Thread.sleep(30);
        TokenUsageRecord.InferenceSpan embedding = TokenUsageRecord.begin(LlmCallType.SIMILAR_TASK_EMBEDDING);
        Thread.sleep(30);
        chat.end();
        Thread.sleep(30);
        embedding.end();

        long inference = record.inferenceMs();
        assertTrue(inference >= 90 && inference < 120, "inference " + inference);

        // Per call type, each call's own duration, so together more than the union.
        JsonObject byCallType = record.toJson().getJsonObject("byCallType");
        long chatMs = byCallType.getJsonObject("path-selection").getLong("inferenceMs");
        long embeddingMs = byCallType.getJsonObject("similar-task-embedding").getLong("inferenceMs");
        assertTrue(chatMs >= 60 && embeddingMs >= 60);
        assertTrue(chatMs + embeddingMs > inference);
    }

    @Test
    void spanReportsToTheRecordItBeganWith() throws InterruptedException {
        TokenUsageRecord task = new TokenUsageRecord();
        TokenUsageRecord.active = task;
        TokenUsageRecord.InferenceSpan span = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        TokenUsageRecord.active = new TokenUsageRecord();
        Thread.sleep(20);
        span.end();

        assertTrue(task.inferenceMs() >= 20);
        assertEquals(0, TokenUsageRecord.active.inferenceMs());
    }

    @Test
    void freezeClipsCallsInFlightAndIgnoresLaterOnes() throws InterruptedException {
        TokenUsageRecord record = new TokenUsageRecord();
        TokenUsageRecord.active = record;

        TokenUsageRecord.InferenceSpan inFlight = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        Thread.sleep(30);
        record.freezeInference(System.nanoTime());
        long frozen = record.inferenceMs();
        assertTrue(frozen >= 30 && frozen < 50, "frozen " + frozen);

        Thread.sleep(30);
        inFlight.end();
        TokenUsageRecord.InferenceSpan late = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        late.attemptFailed();
        Thread.sleep(10);
        late.fail();

        assertEquals(frozen, record.inferenceMs());
        assertEquals(0, record.failedAttempts);
        assertEquals(0, record.failedCalls);
    }

    @Test
    void countsFailedCallsAndAttempts() {
        TokenUsageRecord record = new TokenUsageRecord();
        TokenUsageRecord.active = record;

        TokenUsageRecord.InferenceSpan retried = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        retried.attemptFailed();
        retried.attemptFailed();
        retried.end();
        TokenUsageRecord.InferenceSpan failed = TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP);
        failed.fail();
        failed.end(); // Already ended, so not counted again.

        JsonObject json = record.toJson();
        assertEquals(2, json.getInteger("failedAttempts"));
        assertEquals(1, json.getInteger("failedCalls"));
        assertEquals(1, json.getJsonObject("byCallType").getJsonObject("uncharted-step").getInteger("failedCalls"));
        assertEquals(1, json.getJsonObject("byKind").getJsonObject("chat_completion").getInteger("failedCalls"));
    }
}
