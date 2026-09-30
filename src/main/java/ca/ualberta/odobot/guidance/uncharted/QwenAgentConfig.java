package ca.ualberta.odobot.guidance.uncharted;

import io.vertx.core.json.JsonObject;

import java.time.Duration;

/**
 * Settings for {@link Qwen38Agent}. The defaults are those of OSWorld's {@code run_multienv_qwen3_8.py}.
 *
 * @param model           model name sent to the OpenAI-compatible server.
 * @param baseUrl         base URL of the OpenAI-compatible server, including {@code /v1}.
 * @param apiKey          API key; local servers accept any value.
 * @param maxTokens       completion token limit, sent as {@code max_tokens}.
 * @param topK            sent as the non-standard {@code top_k} body field; null to omit.
 * @param presencePenalty sent as the {@code presence_penalty} body field; null to omit.
 * @param historyN        number of most recent steps replayed as conversation turns; older steps only appear in
 *                        the "Previous actions" text.
 * @param imageMax        maximum number of screenshots kept in the conversation before older ones are collapsed.
 * @param foldSize        number of screenshots collapsed at a time once {@code imageMax} is exceeded.
 * @param coordinateType  RELATIVE: the model answers on a 1000x1000 grid. ABSOLUTE: in resized-screenshot pixels.
 * @param collapseText    text that replaces a collapsed screenshot.
 * @param timeout         per-request timeout.
 * @param maxAttempts     total attempts per LLM call (OSWorld's {@code OSWORLD_MAX_RETRY_TIMES}).
 */
public record QwenAgentConfig(
        String model,
        String baseUrl,
        String apiKey,
        int maxTokens,
        double temperature,
        double topP,
        Integer topK,
        Double presencePenalty,
        int historyN,
        int imageMax,
        int foldSize,
        CoordinateType coordinateType,
        String collapseText,
        Duration timeout,
        int maxAttempts
) {

    public enum CoordinateType { RELATIVE, ABSOLUTE }

    public static final String DEFAULT_MODEL = "qwen3.8-27b";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080/v1";
    public static final String DEFAULT_API_KEY = "dummy";
    public static final String DEFAULT_COLLAPSE_TEXT = "This screenshot has been collapsed.";

    public QwenAgentConfig {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (coordinateType == null) {
            throw new IllegalArgumentException("coordinateType is required");
        }
        if (imageMax < 1) {
            throw new IllegalArgumentException("imageMax must be >= 1");
        }
        if (foldSize < 1) {
            throw new IllegalArgumentException("foldSize must be >= 1");
        }
        if (historyN < 0) {
            throw new IllegalArgumentException("historyN must be >= 0");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (apiKey == null) {
            apiKey = DEFAULT_API_KEY;
        }
        if (collapseText == null) {
            collapseText = DEFAULT_COLLAPSE_TEXT;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(130);
        }
    }

    public static QwenAgentConfig defaults() {
        return fromJson(new JsonObject());
    }

    /**
     * Keys mirror the command line flags of {@code run_multienv_qwen3_8.py}: {@code model}, {@code base_url},
     * {@code api_key}, {@code max_tokens}, {@code temperature}, {@code top_p}, {@code top_k},
     * {@code presence_penalty}, {@code history_n}, {@code image_max}, {@code fold_size}, {@code coord}
     * ("relative" or "absolute"), {@code collapse_text}, {@code timeout_seconds} and {@code max_attempts}.
     * Missing keys take the defaults. {@code top_k} and {@code presence_penalty} may be null to omit them.
     */
    public static QwenAgentConfig fromJson(JsonObject json) {
        return new QwenAgentConfig(
                json.getString("model", DEFAULT_MODEL),
                json.getString("base_url", DEFAULT_BASE_URL),
                json.getString("api_key", DEFAULT_API_KEY),
                json.getInteger("max_tokens", 4096),
                json.getDouble("temperature", 1.0),
                json.getDouble("top_p", 0.95),
                json.containsKey("top_k") ? json.getInteger("top_k") : Integer.valueOf(20),
                json.getDouble("presence_penalty"),
                json.getInteger("history_n", 5),
                json.getInteger("image_max", 5),
                json.getInteger("fold_size", 2),
                CoordinateType.valueOf(json.getString("coord", "relative").toUpperCase()),
                json.getString("collapse_text", DEFAULT_COLLAPSE_TEXT),
                Duration.ofMillis(Math.round(json.getDouble("timeout_seconds", 130.0) * 1000)),
                json.getInteger("max_attempts", 5)
        );
    }
}
