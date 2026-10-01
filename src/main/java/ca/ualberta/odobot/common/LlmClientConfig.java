package ca.ualberta.odobot.common;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.vertx.core.json.JsonObject;

import java.time.Duration;

/**
 * The OpenAI client settings shared by the charted and uncharted agents, from the {@code llm} object of an evaluate
 * request. The defaults are those of OSWorld's {@code run_multienv_qwen3_8.py}, pointing at a local server.
 *
 * <p>While a task runs, the harness installs its settings as {@link #active}. Every chat completion made through
 * {@link AbstractOpenAIStrategy} then uses them instead of the service's yaml configuration.</p>
 *
 * @param model           model name sent to the server.
 * @param baseUrl         base URL of the OpenAI-compatible server, including {@code /v1}; null for the OpenAI API.
 * @param apiKey          API key; null to use the key the caller falls back on, see {@link #resolveApiKey}.
 * @param maxTokens       completion token limit, see {@link #applyTo}; null to omit.
 * @param temperature     null to omit.
 * @param topP            null to omit.
 * @param topK            sent as the non-standard {@code top_k} body field; null to omit.
 * @param presencePenalty sent as the {@code presence_penalty} body field; null to omit.
 * @param extraBody       fields merged into the request body as they are, e.g. {@code chat_template_kwargs}.
 * @param timeout         per-request timeout.
 * @param maxAttempts     total attempts per LLM call.
 */
public record LlmClientConfig(
        String model,
        String baseUrl,
        String apiKey,
        Integer maxTokens,
        Double temperature,
        Double topP,
        Integer topK,
        Double presencePenalty,
        JsonObject extraBody,
        Duration timeout,
        int maxAttempts
) {

    public static final String DEFAULT_MODEL = "qwen3.8-27b";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080/v1";
    public static final String DEFAULT_API_KEY = "dummy";

    /**
     * The settings of the task being executed, or null to use each service's yaml configuration.
     */
    public static volatile LlmClientConfig active;

    public LlmClientConfig {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max_attempts must be >= 1");
        }
        if (extraBody == null) {
            extraBody = new JsonObject();
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(130);
        }
    }

    public static LlmClientConfig defaults() {
        return fromJson(new JsonObject());
    }

    /**
     * Keys: {@code model}, {@code base_url}, {@code api_key}, {@code max_tokens}, {@code temperature}, {@code top_p},
     * {@code top_k}, {@code presence_penalty}, {@code extra_body}, {@code timeout_seconds} and {@code max_attempts}.
     * Missing keys take the defaults; a key set to null is not sent ({@code base_url}: null means the OpenAI API;
     * {@code api_key}: missing or null means the caller's fallback, see {@link #resolveApiKey}).
     */
    public static LlmClientConfig fromJson(JsonObject json) {
        return new LlmClientConfig(
                json.getString("model", DEFAULT_MODEL),
                json.containsKey("base_url") ? json.getString("base_url") : DEFAULT_BASE_URL,
                json.getString("api_key"),
                json.containsKey("max_tokens") ? json.getInteger("max_tokens") : Integer.valueOf(4096),
                json.containsKey("temperature") ? json.getDouble("temperature") : Double.valueOf(1.0),
                json.containsKey("top_p") ? json.getDouble("top_p") : Double.valueOf(0.95),
                json.containsKey("top_k") ? json.getInteger("top_k") : Integer.valueOf(20),
                json.getDouble("presence_penalty"),
                json.getJsonObject("extra_body", new JsonObject()),
                Duration.ofMillis(Math.round(json.getDouble("timeout_seconds", 130.0) * 1000)),
                json.getInteger("max_attempts", 5)
        );
    }

    /**
     * @param fallback the key to use with the OpenAI API when none is set, e.g. a service's yaml {@code secretKey}, so
     *                 task files need not hold it. It is never sent to other servers, which get {@value #DEFAULT_API_KEY}.
     * @return the API key to send.
     */
    public String resolveApiKey(String fallback) {
        if (apiKey != null) {
            return apiKey;
        }
        return isOpenAiPlatform() && fallback != null? fallback : DEFAULT_API_KEY;
    }

    /**
     * @return true if requests go to the OpenAI API rather than another OpenAI-compatible server.
     */
    public boolean isOpenAiPlatform() {
        return baseUrl == null || baseUrl.contains("api.openai.com");
    }

    /**
     * Set the model and sampling settings of a request. The token limit is sent as {@code max_completion_tokens} to
     * the OpenAI API, whose newer models reject {@code max_tokens}, and as {@code max_tokens} to other servers.
     */
    public ChatCompletionCreateParams.Builder applyTo(ChatCompletionCreateParams.Builder builder) {
        builder.model(model);
        if (maxTokens != null) {
            if (isOpenAiPlatform()) {
                builder.maxCompletionTokens(maxTokens);
            } else {
                builder.maxTokens(maxTokens);
            }
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (topP != null) {
            builder.topP(topP);
        }
        // Not part of the OpenAI schema; local servers read them straight off the request body.
        if (topK != null) {
            builder.putAdditionalBodyProperty("top_k", JsonValue.from(topK));
        }
        if (presencePenalty != null) {
            builder.putAdditionalBodyProperty("presence_penalty", JsonValue.from(presencePenalty));
        }
        //Re-decoding gives plain maps and lists, which JsonValue understands.
        new JsonObject(extraBody.encode()).getMap()
                .forEach((key, value) -> builder.putAdditionalBodyProperty(key, JsonValue.from(value)));
        return builder;
    }
}
