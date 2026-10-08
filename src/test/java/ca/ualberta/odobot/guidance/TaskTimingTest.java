package ca.ualberta.odobot.guidance;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskTimingTest {

    @Test
    void leavesOutPhasesThatWereNotReached() {
        TaskTiming timing = new TaskTiming();
        timing.markBeforeSetup();
        timing.markExecutionStart();

        JsonObject json = timing.toJson();

        assertTrue(json.containsKey("beforeSetup"));
        assertTrue(json.containsKey("executionStart"));
        assertTrue(json.containsKey("setupMs"));
        assertFalse(json.containsKey("executionEnd"));
        assertFalse(json.containsKey("executionMs"));
        assertFalse(json.containsKey("scoringMs"));
        assertFalse(json.containsKey("totalMs"));
        assertNull(timing.afterScoring());
    }

    @Test
    void phasesAddUpToTheTotal() throws InterruptedException {
        TaskTiming timing = new TaskTiming();
        timing.markBeforeSetup();
        Thread.sleep(20);
        timing.markExecutionStart();
        Thread.sleep(20);
        timing.markExecutionEnd();
        timing.markArtifactsSaved();
        timing.markScoringStart();
        Thread.sleep(20);
        timing.markScoringEnd();
        timing.markAfterScoring();

        assertTrue(timing.setupMs() >= 20);
        assertTrue(timing.executionMs() >= 20);
        assertTrue(timing.scoringMs() >= 20);
        long phases = timing.setupMs() + timing.executionMs() + timing.artifactsMs() + timing.scoringMs();
        // Each phase is truncated to whole milliseconds, and nothing happens between the phases here.
        assertTrue(timing.totalMs() - phases >= 0 && timing.totalMs() - phases <= 4, "total " + timing.totalMs() + " vs phases " + phases);
    }

    @Test
    void keepsTheFirstMark() throws InterruptedException {
        TaskTiming timing = new TaskTiming();
        timing.markBeforeSetup();
        timing.markExecutionStart();
        timing.markExecutionEnd();
        Thread.sleep(20);
        timing.markExecutionEnd();

        assertTrue(timing.executionMs() < 20);
        assertTrue(timing.executionStarted());
    }

    @Test
    void splitsExecutionIntoInferenceAndOtherWork() throws InterruptedException {
        TaskTiming timing = new TaskTiming();
        timing.markExecutionStart();
        Thread.sleep(30);
        timing.markExecutionEnd();

        long execution = timing.executionMs();
        JsonObject json = timing.toJson(10L);
        assertEquals(10L, json.getLong("inferenceMs"));
        assertEquals(execution, json.getLong("inferenceMs") + json.getLong("otherExecutionMs"));

        // Inference is capped at execution, so the other work never goes negative.
        assertEquals(execution, timing.inferenceMs(execution + 5));
        assertEquals(0L, timing.otherExecutionMs(execution + 5));

        assertFalse(timing.toJson().containsKey("inferenceMs"));
        assertFalse(timing.toJson().containsKey("otherExecutionMs"));
    }

    @Test
    void noSplitBeforeExecutionEnds() {
        TaskTiming timing = new TaskTiming();
        timing.markExecutionStart();
        assertNull(timing.inferenceMs(10L));
        assertFalse(timing.toJson(10L).containsKey("inferenceMs"));
    }
}
