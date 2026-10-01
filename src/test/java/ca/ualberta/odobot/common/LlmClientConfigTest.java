package ca.ualberta.odobot.common;

import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientConfigTest {

    private static ChatCompletionCreateParams build(LlmClientConfig config) {
        return config.applyTo(ChatCompletionCreateParams.builder().addUserMessage("hi")).build();
    }

    @Test
    void defaultsAreOSWorldsLocalServerSettings() {
        LlmClientConfig config = LlmClientConfig.defaults();
        assertEquals("qwen3.8-27b", config.model());
        assertEquals("http://127.0.0.1:8080/v1", config.baseUrl());
        assertNull(config.apiKey());
        assertEquals("dummy", config.resolveApiKey(null));
        assertEquals(4096, config.maxTokens());
        assertEquals(1.0, config.temperature());
        assertEquals(0.95, config.topP());
        assertEquals(20, config.topK());
        assertNull(config.presencePenalty());
        assertEquals(Duration.ofSeconds(130), config.timeout());
        assertEquals(5, config.maxAttempts());
        assertFalse(config.isOpenAiPlatform());
    }

    @Test
    void localServerGetsMaxTokensAndNonStandardFields() {
        LlmClientConfig config = LlmClientConfig.fromJson(new JsonObject()
                .put("presence_penalty", 0.5)
                .put("extra_body", new JsonObject().put("chat_template_kwargs", new JsonObject().put("enable_thinking", false))));
        ChatCompletionCreateParams params = build(config);

        assertEquals("qwen3.8-27b", params.model().asString());
        assertEquals(4096L, params.maxTokens().orElseThrow());
        assertTrue(params.maxCompletionTokens().isEmpty());
        assertEquals(1.0, params.temperature().orElseThrow());
        assertEquals(0.95, params.topP().orElseThrow());

        Map<String, JsonValue> extra = params._additionalBodyProperties();
        assertEquals(JsonValue.from(20), extra.get("top_k"));
        assertEquals(JsonValue.from(0.5), extra.get("presence_penalty"));
        assertEquals(JsonValue.from(Map.of("enable_thinking", false)), extra.get("chat_template_kwargs"));
    }

    @Test
    void openAiGetsMaxCompletionTokensAndNullKeysAreOmitted() {
        LlmClientConfig config = LlmClientConfig.fromJson(new JsonObject()
                .put("model", "gpt-5.6-luna")
                .putNull("base_url")
                .put("api_key", "sk-test")
                .putNull("temperature")
                .putNull("top_p")
                .putNull("top_k"));
        assertTrue(config.isOpenAiPlatform());

        ChatCompletionCreateParams params = build(config);
        assertEquals(4096L, params.maxCompletionTokens().orElseThrow());
        assertTrue(params.maxTokens().isEmpty());
        assertTrue(params.temperature().isEmpty());
        assertTrue(params.topP().isEmpty());
        assertTrue(params._additionalBodyProperties().isEmpty());

        assertTrue(LlmClientConfig.fromJson(new JsonObject().put("base_url", "https://api.openai.com/v1")).isOpenAiPlatform());
    }

    @Test
    void openAiFallsBackOnTheCallersKeyButLocalServersNeverGetIt() {
        LlmClientConfig openAi = LlmClientConfig.fromJson(new JsonObject().put("model", "gpt-5.6-luna").putNull("base_url"));
        assertEquals("sk-yaml", openAi.resolveApiKey("sk-yaml"));
        assertEquals("dummy", openAi.resolveApiKey(null));

        LlmClientConfig local = LlmClientConfig.defaults();
        assertEquals("dummy", local.resolveApiKey("sk-yaml"));

        //An explicit key always wins.
        LlmClientConfig explicit = LlmClientConfig.fromJson(new JsonObject().putNull("base_url").put("api_key", "sk-file"));
        assertEquals("sk-file", explicit.resolveApiKey("sk-yaml"));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> LlmClientConfig.fromJson(new JsonObject().put("model", " ")));
        assertThrows(IllegalArgumentException.class, () -> LlmClientConfig.fromJson(new JsonObject().put("max_attempts", 0)));
    }

    @Test
    void qwenConfigTakesClientKeysFromLlmOverQwen() {
        JsonObject qwen = new JsonObject()
                .put("model", "old-model")
                .put("base_url", "http://old:8080/v1")
                .put("top_k", 40)
                .put("history_n", 3);
        JsonObject llm = new JsonObject()
                .put("model", "new-model")
                .putNull("top_k");

        QwenAgentConfig config = QwenAgentConfig.fromJson(qwen, llm);
        assertEquals("new-model", config.client().model());
        assertEquals("http://old:8080/v1", config.client().baseUrl()); //only in qwen, still used
        assertNull(config.client().topK());                             //llm's null wins
        assertEquals(3, config.historyN());

        //Older task files with only a qwen object keep working.
        assertEquals("old-model", QwenAgentConfig.fromJson(qwen).client().model());
    }
}
