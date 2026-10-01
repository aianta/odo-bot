package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallType;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Token usage and LLM call counts, in total and per {@link LlmCallType}.
 *
 * <p>LLM clients report every call to {@link #active}, the record the current owner has installed. During task
 * execution that is the {@link RequestManager}'s {@link TaskTokenUsage}.</p>
 */
public class TokenUsageRecord {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecord.class);

    /**
     * The record every LLM call in this JVM is reported to, or null if usage is not being recorded.
     */
    public static volatile TokenUsageRecord active;

    /**
     * Record one LLM call in the {@link #active} record, if there is one.
     */
    public static void report(LlmCallType type, long inputTokens, long outputTokens, long totalTokens){
        TokenUsageRecord record = active;
        if(record != null){
            log.info("Usage Record: {} {} call [input: {}, output: {}, total: {}]", record, type.label, inputTokens, outputTokens, totalTokens);
            record.record(type, inputTokens, outputTokens, totalTokens);
        }
    }

    public int inputTokens = 0;
    public int outputTokens = 0;
    public int totalTokens = 0;
    public int llmCalls = 0;

    private final Map<LlmCallType, Counts> byCallType = new EnumMap<>(LlmCallType.class);

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
                .put("byKind", kinds)
                .put("byCallType", callTypes);
    }

    /**
     * Call count and token sums of a group of calls, with the min/max/mean tokens of a single call.
     */
    private static class Counts {
        int llmCalls = 0;
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
            inputTokens.add(other.inputTokens);
            outputTokens.add(other.outputTokens);
            totalTokens.add(other.totalTokens);
        }

        JsonObject toJson(){
            return new JsonObject()
                    .put("llmCalls", llmCalls)
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
