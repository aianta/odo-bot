package ca.ualberta.odobot.task.webarena;

import ca.ualberta.odobot.common.LlmClientConfig;
import io.vertx.core.json.JsonObject;

/**
 * The {@code webarenaEvaluation} object of an evaluate request:
 * <pre>
 * "webarenaEvaluation": {
 *   "judge": {"model": "...", "base_url": "...", "api_key": "...", ...},
 *   "accounts": {"gitlab": {"username": "...", "password": "..."}},
 *   "pageSettleMs": 3000
 * }
 * </pre>
 *
 * @param judge the LLM judge's client settings, or null if the request sets none; tasks that need the judge then end in
 *              ERROR. Unset keys take WebArena's settings: model gpt-4-1106-preview on the OpenAI API, temperature 0,
 *              top_p 1, 768 max tokens, no top_k.
 * @param accounts WebArena's accounts, with the request's overrides.
 * @param pageSettleMs how long program_html waits after each navigation, WebArena's {@code time.sleep(3)} by default.
 */
public record WebArenaEvaluationSettings(LlmClientConfig judge, WebArenaAccounts accounts, long pageSettleMs) {

    public static final String FIELD = "webarenaEvaluation";

    /**
     * WebArena's judge settings ({@code llm_fuzzy_match}), under any the request sets.
     */
    static JsonObject judgeDefaults(){
        return new JsonObject()
                .put("model", "gpt-4-1106-preview")
                .putNull("base_url")
                .put("temperature", 0.0)
                .put("top_p", 1.0)
                .put("max_tokens", 768)
                .putNull("top_k");
    }

    /**
     * @throws IllegalArgumentException if the settings are invalid.
     */
    public static WebArenaEvaluationSettings fromRequest(JsonObject request){
        Object raw = request.getValue(FIELD);
        if(raw != null && !(raw instanceof JsonObject)){
            throw new IllegalArgumentException("'%s' must be an object.".formatted(FIELD));
        }
        JsonObject settings = raw == null? new JsonObject() : (JsonObject) raw;

        LlmClientConfig judge = null;
        Object judgeJson = settings.getValue("judge");
        if(judgeJson != null){
            if(!(judgeJson instanceof JsonObject j)){
                throw new IllegalArgumentException("'%s.judge' must be an object.".formatted(FIELD));
            }
            judge = LlmClientConfig.fromJson(judgeDefaults().mergeIn(j));
        }

        Object settle = settings.getValue("pageSettleMs", WebArenaEndState.DEFAULT_PAGE_SETTLE_MS);
        if(!(settle instanceof Number n) || n.longValue() < 0){
            throw new IllegalArgumentException("'%s.pageSettleMs' must be a number of milliseconds.".formatted(FIELD));
        }
        return new WebArenaEvaluationSettings(judge, WebArenaAccounts.fromJson(settings.getJsonObject("accounts")), n.longValue());
    }
}
