package ca.ualberta.odobot.common;

import ca.ualberta.odobot.guidance.TokenUsageRecord;

/**
 * The LLM settings and token usage record of one task. LLM clients built for a task are given its scope, so tasks that
 * run at the same time each count their own calls and use their own client settings.
 *
 * <p>The scope is created before the task starts, so agents and services can be built with it. The harness installs the
 * task's record and settings when the task starts and removes them when it ends; calls outside that window are not
 * counted and use each client's own configuration.</p>
 */
public class LlmCallScope {

    private volatile TokenUsageRecord usage;
    private volatile LlmClientConfig config;

    /**
     * Count calls toward the given record and use the given client settings.
     *
     * @param config null to use each client's own configuration.
     */
    public void start(TokenUsageRecord usage, LlmClientConfig config){
        this.usage = usage;
        this.config = config;
    }

    /**
     * Stop counting calls toward the task.
     */
    public void stopCounting(){
        this.usage = null;
    }

    /**
     * Go back to each client's own configuration.
     */
    public void clearConfig(){
        this.config = null;
    }

    /**
     * @return the task's client settings, or null to use the client's own configuration.
     */
    public LlmClientConfig config(){
        return config;
    }

    /**
     * Start timing one LLM call against the task's record. The span reports to that record even if the task ends
     * before the call does.
     */
    public TokenUsageRecord.InferenceSpan begin(LlmCallType type){
        TokenUsageRecord record = usage;
        return record == null? TokenUsageRecord.InferenceSpan.NONE : record.span(type);
    }

    /**
     * Record one LLM call in the task's record, if it is still counting.
     */
    public void report(LlmCallType type, long inputTokens, long outputTokens, long totalTokens){
        TokenUsageRecord record = usage;
        if(record != null){
            record.reportCall(type, inputTokens, outputTokens, totalTokens);
        }
    }
}
