package ca.ualberta.odobot.common;

import ca.ualberta.odobot.guidance.TokenUsageRecord;
import ca.ualberta.odobot.modelconstruction.linklabeling.impl.LinkLabelingServiceImpl;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The charted services' chat completions use the task's {@link LlmClientConfig} when one is active.
 */
class AbstractOpenAIStrategyLlmConfigTest {

    private Vertx vertx;
    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeServer() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            requests.add(body.toJsonObject());
            JsonObject completion = new JsonObject()
                    .put("id", "chatcmpl-1")
                    .put("object", "chat.completion")
                    .put("created", 0)
                    .put("model", body.toJsonObject().getString("model"))
                    .put("usage", new JsonObject().put("prompt_tokens", 50).put("completion_tokens", 7).put("total_tokens", 57))
                    .put("choices", new JsonArray().add(new JsonObject()
                            .put("index", 0)
                            .put("finish_reason", "stop")
                            .put("message", new JsonObject()
                                    .put("role", "assistant")
                                    .put("content", "<think>\nIt links to one announcement.\n</think>\n\nannouncement"))));
            request.response().putHeader("content-type", "application/json").end(completion.encode());
        })).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        LlmClientConfig.active = null;
        TokenUsageRecord.active = null;
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private String serverUrl() {
        return "http://127.0.0.1:%d/v1".formatted(server.actualPort());
    }

    private LinkLabelingServiceImpl linkLabeler(JsonObject openAI) {
        openAI.put("generateLinkLabel", new JsonObject().put("systemPrompt", "Label the link."));
        return new LinkLabelingServiceImpl(new JsonObject().put("openAI", openAI));
    }

    @Test
    void usesTheActiveTaskSettings() {
        //The yaml settings point elsewhere; the active task settings must win.
        LinkLabelingServiceImpl labeler = linkLabeler(new JsonObject()
                .put("secretKey", "yaml-key")
                .put("model", "yaml-model")
                .put("baseUrl", "http://127.0.0.1:1/v1"));
        LlmClientConfig.active = LlmClientConfig.fromJson(new JsonObject()
                .put("base_url", serverUrl())
                .put("model", "qwen3.8-27b")
                .put("max_attempts", 1));
        TokenUsageRecord usage = new TokenUsageRecord();
        TokenUsageRecord.active = usage;

        JsonObject label = labeler.labelLink("/courses/2/announcements/17", "/courses/*/announcements/*").result();

        //The reasoning block is stripped from the answer.
        assertEquals("announcement", label.getString("type"));

        JsonObject body = requests.get(0);
        assertEquals("qwen3.8-27b", body.getString("model"));
        assertEquals(4096, body.getInteger("max_tokens"));
        assertFalse(body.containsKey("max_completion_tokens"));
        assertEquals(1.0, body.getDouble("temperature"));
        assertEquals(0.95, body.getDouble("top_p"));
        assertEquals(20, body.getInteger("top_k"));

        assertEquals(1, usage.llmCalls);
        assertEquals(57, usage.totalTokens);
    }

    @Test
    void usesTheServiceSettingsWithoutActiveTaskSettings() {
        LlmClientConfig.active = null;
        LinkLabelingServiceImpl labeler = linkLabeler(new JsonObject()
                .put("secretKey", "yaml-key")
                .put("model", "yaml-model")
                .put("baseUrl", serverUrl())
                .putNull("temperature"));

        labeler.labelLink("/courses/2/announcements/17", "/courses/*/announcements/*").result();

        JsonObject body = requests.get(0);
        assertEquals("yaml-model", body.getString("model"));
        assertFalse(body.containsKey("temperature"));
        assertFalse(body.containsKey("top_k"));
        assertFalse(body.containsKey("max_tokens"));
    }
}
