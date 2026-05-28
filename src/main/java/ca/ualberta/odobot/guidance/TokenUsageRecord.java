package ca.ualberta.odobot.guidance;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenUsageRecord {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecord.class);

    public TokenUsageRecord merge(TokenUsageRecord other){
        log.info("{} [input:{}, output:{}, total: {}] Merging with {} [input:{}, output:{}, total: {}]",
                this.toString(), this.inputTokens, this.outputTokens, this.totalTokens,
                other.toString(), other.inputTokens, other.outputTokens, other.totalTokens);

        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens += other.totalTokens;
        return this;
    }

    public int inputTokens = 0;
    public int outputTokens = 0;
    public int totalTokens = 0;

    public void addInputTokens(int count){
        log.info("Usage Record: {} [{} + {}] {} input tokens", this.toString(), this.inputTokens, count, this.inputTokens+count);
        inputTokens += count;
    }

    public void addOutputTokens(int count){
        log.info("Usage Record: {} [{} + {}] {} output tokens", this.toString(), this.outputTokens, count, this.outputTokens+count);
        outputTokens += count;
    }

    public void addTotalTokens(int count){
        log.info("Usage Record: {} [{} + {}] {} total tokens", this.toString(), this.totalTokens, count, this.totalTokens+count);
        totalTokens += count;
    }

    public int computeTotalTokens(){
        return inputTokens + outputTokens;
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
                .put("inputTokens", this.inputTokens)
                .put("outputTokens", this.outputTokens)
                .put("totalTokens", this.totalTokens);

        return result;
    }

}
