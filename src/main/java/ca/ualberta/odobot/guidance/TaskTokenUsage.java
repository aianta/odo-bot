package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallType;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The token usage of one task, as tracked by the {@link RequestManager}. Every call is also attributed to the agent
 * that was active when it was reported.
 */
public class TaskTokenUsage extends TokenUsageRecord {

    private final Supplier<String> activeAgentName;

    private final Map<String, TokenUsageRecord> byAgent = new LinkedHashMap<>();

    public TaskTokenUsage(Supplier<String> activeAgentName){
        this.activeAgentName = activeAgentName;
    }

    @Override
    public synchronized void record(LlmCallType type, long inputTokens, long outputTokens, long totalTokens) {
        super.record(type, inputTokens, outputTokens, totalTokens);
        byAgent.computeIfAbsent(activeAgentName.get(), name->new TokenUsageRecord())
                .record(type, inputTokens, outputTokens, totalTokens);
    }

    @Override
    public synchronized JsonObject toJson() {
        JsonObject agents = new JsonObject();
        byAgent.forEach((name, record)->agents.put(name, record.toJson()));
        return super.toJson().put("byAgent", agents);
    }
}
