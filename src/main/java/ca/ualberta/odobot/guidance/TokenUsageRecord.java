package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallType;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Token usage, LLM call counts and time spent waiting on inference, in total and per {@link LlmCallType}.
 *
 * <p>LLM clients built for a task report its calls through the task's {@link ca.ualberta.odobot.common.LlmCallScope}
 * to the {@link RequestManager}'s {@link TaskTokenUsage}. Other clients report to {@link #active}, the process-wide
 * record, if one is installed.</p>
 *
 * <p>Clients also time each call with an {@link InferenceSpan} from {@link #begin}. {@link #inferenceMs()} is the time
 * at least one call was in flight, so overlapping calls are not counted twice; the per call type {@code inferenceMs}
 * is the sum of that type's call durations. A call's span covers its retries.</p>
 */
public class TokenUsageRecord {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecord.class);

    /**
     * The record that LLM calls made outside a task are reported to, or null if they are not being recorded. Calls made
     * for a task go to the task's own record instead, see {@link ca.ualberta.odobot.common.LlmCallScope}.
     */
    public static volatile TokenUsageRecord active;

    /**
     * Record one LLM call in the {@link #active} record, if there is one.
     */
    public static void report(LlmCallType type, long inputTokens, long outputTokens, long totalTokens){
        TokenUsageRecord record = active;
        if(record != null){
            record.reportCall(type, inputTokens, outputTokens, totalTokens);
        }
    }

    /**
     * Start timing one LLM call against the {@link #active} record. The span reports to that record even if another
     * one is installed before the call ends.
     */
    public static InferenceSpan begin(LlmCallType type){
        TokenUsageRecord record = active;
        return record == null? InferenceSpan.NONE : record.span(type);
    }

    /**
     * Record one LLM call in this record.
     */
    public void reportCall(LlmCallType type, long inputTokens, long outputTokens, long totalTokens){
        log.info("Usage Record: {} {} call [input: {}, output: {}, total: {}]", this, type.label, inputTokens, outputTokens, totalTokens);
        record(type, inputTokens, outputTokens, totalTokens);
    }

    /**
     * Start timing one LLM call against this record.
     */
    public InferenceSpan span(LlmCallType type){
        return new InferenceSpan(this, type, callStarted());
    }

    /**
     * The timing of one LLM call. Ending it more than once has no effect.
     */
    public static class InferenceSpan {

        public static final InferenceSpan NONE = new InferenceSpan(null, null, -1);

        private final TokenUsageRecord record;
        private final LlmCallType type;
        private final long startNanos;
        private boolean ended = false;

        private InferenceSpan(TokenUsageRecord record, LlmCallType type, long startNanos){
            this.record = record;
            this.type = type;
            this.startNanos = startNanos;
        }

        /**
         * Count a failed attempt that will be retried within this call.
         */
        public void attemptFailed(){
            if(record != null) record.attemptFailed();
        }

        /**
         * The call returned.
         */
        public void end(){
            finish(false);
        }

        /**
         * The call failed for good.
         */
        public void fail(){
            finish(true);
        }

        private synchronized void finish(boolean failed){
            if(record == null || ended) return;
            ended = true;
            record.callEnded(type, startNanos, failed);
        }
    }

    public int inputTokens = 0;
    public int outputTokens = 0;
    public int totalTokens = 0;
    public int llmCalls = 0;

    public int failedCalls = 0;
    public int failedAttempts = 0;

    private final Map<LlmCallType, Counts> byCallType = new EnumMap<>(LlmCallType.class);

    // Calls in flight, and since when at least one has been. -1 for a span begun after the record was frozen.
    private int inFlight = 0;
    private long busySinceNanos;
    private long inferenceNanos = 0;
    private boolean frozen = false;

    synchronized long callStarted(){
        if(frozen) return -1;
        if(inFlight++ == 0) busySinceNanos = System.nanoTime();
        return System.nanoTime();
    }

    synchronized void callEnded(LlmCallType type, long startNanos, boolean failed){
        if(frozen || startNanos < 0) return;
        long now = System.nanoTime();
        if(--inFlight == 0) inferenceNanos += now - busySinceNanos;

        Counts counts = byCallType.computeIfAbsent(type, t->new Counts());
        counts.inferenceNanos += now - startNanos;
        if(failed){
            counts.failedCalls++;
            failedCalls++;
        }
    }

    synchronized void attemptFailed(){
        if(!frozen) failedAttempts++;
    }

    /**
     * Stop timing inference at the given {@link System#nanoTime()}: calls still in flight count up to then, and calls
     * that begin or end afterwards are ignored.
     */
    public synchronized void freezeInference(long atNanos){
        if(frozen) return;
        frozen = true;
        if(inFlight > 0){
            inferenceNanos += Math.max(0, atNanos - busySinceNanos);
        }
    }

    /**
     * @return the time at least one LLM call was in flight.
     */
    public synchronized long inferenceMs(){
        long nanos = inferenceNanos;
        if(!frozen && inFlight > 0) nanos += System.nanoTime() - busySinceNanos;
        return nanos / 1_000_000;
    }

    public synchronized void record(LlmCallType type, long inputTokens, long outputTokens, long totalTokens){
        this.inputTokens += Math.toIntExact(inputTokens);
        this.outputTokens += Math.toIntExact(outputTokens);
        this.totalTokens += Math.toIntExact(totalTokens);
        this.llmCalls++;

        byCallType.computeIfAbsent(type, t->new Counts()).add(inputTokens, outputTokens, totalTokens);
    }

    public synchronized JsonObject toJson(){
        Map<LlmCallType.Kind, Counts> byKind = new EnumMap<>(LlmCallType.Kind.class);
        JsonObject callTypes = new JsonObject();
        byCallType.forEach((type, counts)->{
            byKind.computeIfAbsent(type.kind, k->new Counts()).add(counts);
            callTypes.put(type.label, counts.toJson().put("kind", type.kind.label));
        });

        JsonObject kinds = new JsonObject();
        byKind.forEach((kind, counts)->kinds.put(kind.label, counts.toJson()));

        return new JsonObject()
                .put("inputTokens", this.inputTokens)
                .put("outputTokens", this.outputTokens)
                .put("totalTokens", this.totalTokens)
                .put("llmCalls", this.llmCalls)
                .put("inferenceMs", inferenceMs())
                .put("failedCalls", this.failedCalls)
                .put("failedAttempts", this.failedAttempts)
                .put("byKind", kinds)
                .put("byCallType", callTypes);
    }

    /**
     * Call count, token sums and summed call durations of a group of calls, with the min/max/mean tokens of a single call.
     */
    private static class Counts {
        int llmCalls = 0;
        int failedCalls = 0;
        long inferenceNanos = 0;
        final PerCall inputTokens = new PerCall();
        final PerCall outputTokens = new PerCall();
        final PerCall totalTokens = new PerCall();

        void add(long input, long output, long total){
            llmCalls++;
            inputTokens.add(Math.toIntExact(input));
            outputTokens.add(Math.toIntExact(output));
            totalTokens.add(Math.toIntExact(total));
        }

        void add(Counts other){
            llmCalls += other.llmCalls;
            failedCalls += other.failedCalls;
            inferenceNanos += other.inferenceNanos;
            inputTokens.add(other.inputTokens);
            outputTokens.add(other.outputTokens);
            totalTokens.add(other.totalTokens);
        }

        JsonObject toJson(){
            return new JsonObject()
                    .put("llmCalls", llmCalls)
                    .put("failedCalls", failedCalls)
                    .put("inferenceMs", inferenceNanos / 1_000_000)
                    .put("inputTokens", inputTokens.sum)
                    .put("outputTokens", outputTokens.sum)
                    .put("totalTokens", totalTokens.sum)
                    .put("perCall", new JsonObject()
                            .put("inputTokens", inputTokens.toJson(llmCalls))
                            .put("outputTokens", outputTokens.toJson(llmCalls))
                            .put("totalTokens", totalTokens.toJson(llmCalls)));
        }
    }

    private static class PerCall {
        int sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        void add(int tokens){
            sum += tokens;
            min = Math.min(min, tokens);
            max = Math.max(max, tokens);
        }

        void add(PerCall other){
            sum += other.sum;
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
        }

        JsonObject toJson(int calls){
            return new JsonObject()
                    .put("min", calls == 0? 0 : min)
                    .put("max", calls == 0? 0 : max)
                    .put("mean", calls == 0? 0.0 : (double) sum / calls);
        }
    }

}
