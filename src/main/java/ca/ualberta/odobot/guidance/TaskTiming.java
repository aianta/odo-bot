package ca.ualberta.odobot.guidance;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

/**
 * The wall clock time of one evaluation task, saved with its token usage in {@code <evalId>-tokens.json}.
 *
 * <p>The harness marks the phases of the task as it goes: setup ({@link #markBeforeSetup} to {@link #markExecutionStart}),
 * execution (to {@link #markExecutionEnd}), saving its artifacts (to {@link #markArtifactsSaved}), and scoring
 * ({@link #markScoringStart} to {@link #markScoringEnd}), and the task ends at {@link #markAfterScoring}. Durations come
 * from {@link System#nanoTime()}, so clock adjustments do not skew them; the instants are only for the timestamps.</p>
 *
 * <p>Each mark keeps its first value. Phases whose marks were not taken are left out.</p>
 */
public class TaskTiming {

    private record Mark(Instant at, long nanos){
        static Mark now(){
            return new Mark(Instant.now(), System.nanoTime());
        }
    }

    private Mark beforeSetup;
    private Mark executionStart;
    private Mark executionEnd;
    private Mark artifactsSaved;
    private Mark scoringStart;
    private Mark scoringEnd;
    private Mark afterScoring;

    public synchronized void markBeforeSetup(){
        if(beforeSetup == null) beforeSetup = Mark.now();
    }

    public synchronized void markExecutionStart(){
        if(executionStart == null) executionStart = Mark.now();
    }

    public synchronized void markExecutionEnd(){
        if(executionEnd == null) executionEnd = Mark.now();
    }

    public synchronized void markArtifactsSaved(){
        if(artifactsSaved == null) artifactsSaved = Mark.now();
    }

    public synchronized void markScoringStart(){
        if(scoringStart == null) scoringStart = Mark.now();
    }

    public synchronized void markScoringEnd(){
        if(scoringEnd == null) scoringEnd = Mark.now();
    }

    public synchronized void markAfterScoring(){
        if(afterScoring == null) afterScoring = Mark.now();
    }

    public synchronized boolean executionStarted(){
        return executionStart != null;
    }

    /**
     * @return from before the browser is set up until the agent starts, or null if not reached.
     */
    public synchronized Long setupMs(){
        return between(beforeSetup, executionStart);
    }

    /**
     * @return from the start of the agent until the task completed, failed or timed out, or null if not reached.
     */
    public synchronized Long executionMs(){
        return between(executionStart, executionEnd);
    }

    /**
     * @return the time spent saving the history and raw events of the task, or null if not reached.
     */
    public synchronized Long artifactsMs(){
        return between(executionEnd, artifactsSaved);
    }

    /**
     * @return the time spent in the evaluation script, or null if the task was not scored.
     */
    public synchronized Long scoringMs(){
        return between(scoringStart, scoringEnd);
    }

    /**
     * @return from before the browser is set up until after scoring, or null if not reached.
     */
    public synchronized Long totalMs(){
        return between(beforeSetup, afterScoring);
    }

    public synchronized Instant beforeSetup(){
        return beforeSetup == null? null : beforeSetup.at();
    }

    public synchronized Instant afterScoring(){
        return afterScoring == null? null : afterScoring.at();
    }

    public synchronized JsonObject toJson(){
        JsonObject json = new JsonObject();
        putInstant(json, "beforeSetup", beforeSetup);
        putInstant(json, "executionStart", executionStart);
        putInstant(json, "executionEnd", executionEnd);
        putInstant(json, "afterScoring", afterScoring);
        putMs(json, "setupMs", setupMs());
        putMs(json, "executionMs", executionMs());
        putMs(json, "artifactsMs", artifactsMs());
        putMs(json, "scoringMs", scoringMs());
        putMs(json, "totalMs", totalMs());
        return json;
    }

    private static Long between(Mark from, Mark to){
        return from == null || to == null? null : (to.nanos() - from.nanos()) / 1_000_000;
    }

    private static void putInstant(JsonObject json, String key, Mark mark){
        if(mark != null) json.put(key, mark.at().toString());
    }

    private static void putMs(JsonObject json, String key, Long ms){
        if(ms != null) json.put(key, ms);
    }
}
