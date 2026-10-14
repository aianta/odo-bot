package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.common.LlmCallType;
import ca.ualberta.odobot.common.LlmClientConfig;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * The WebArena judge over an OpenAI-compatible chat completion API, with its own client settings
 * ({@code webarenaEvaluation.judge}) and its own token usage record, so its calls never count as the agent's.
 * Like every client here, it drops a {@code <think>} block the server returns inside the answer.
 */
public class OpenAiWebArenaJudge extends AbstractOpenAIStrategy implements WebArenaJudge {

    private final LlmClientConfig config;

    /**
     * @param config the judge's client settings, see {@link WebArenaEvaluationSettings}.
     * @param scope counts the judge's calls; its config is the judge's.
     */
    public OpenAiWebArenaJudge(LlmClientConfig config, LlmCallScope scope){
        super(new JsonObject().put("openAI", new JsonObject()
                //The key used with the OpenAI API when the judge config sets none.
                .put("secretKey", fallbackApiKey())
                .put("model", config.model())), scope);
        this.config = config;
    }

    private static String fallbackApiKey(){
        String key = System.getenv("OPENAI_API_KEY");
        return key == null || key.isBlank()? LlmClientConfig.DEFAULT_API_KEY : key;
    }

    public LlmClientConfig config(){
        return config;
    }

    @Override
    public String complete(List<Message> messages) {
        return executeChatCompletion(LlmCallType.WEBARENA_JUDGE, messages.stream()
                .map(m->"system".equals(m.role())? system(m.content()) : user(m.content()))
                .toList());
    }
}
