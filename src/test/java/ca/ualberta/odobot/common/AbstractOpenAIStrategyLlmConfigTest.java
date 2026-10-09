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
 * The charted services' chat completions use the settings of the task they are built for, and are counted toward it.
 */
class AbstractOpenAIStrategyLlmConfigTest {

    private Vertx vertx;
    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    /**
     * A strategy that asks one question, built for a task or not.
     */
    static class AskingStrategy extends AbstractOpenAIStrategy {
        AskingStrategy(JsonObject openAI, LlmCallScope scope){
            super(new JsonObject().put("openAI", openAI), scope);
        }

        String ask(){
            return executeChatCompletion(List.of(user("Label the link.")));
        }
    }

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
        TokenUsageRecord.active = null;
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private String serverUrl() {
        return "http://127.0.0.1:%d/v1".formatted(server.actualPort());
    }

    /**
     * Settings like a service's yaml, which point elsewhere so a call that uses them fails.
     */
    private static JsonObject unreachableYaml(){
        return new JsonObject()
                .put("secretKey", "yaml-key")
                .put("model", "yaml-model")
                .put("baseUrl", "http://127.0.0.1:1/v1");
    }

    private LlmClientConfig taskSettings(String model){
        return LlmClientConfig.fromJson(new JsonObject()
                .put("base_url", serverUrl())
                .put("model", model)
                .put("max_attempts", 1));
    }

    @Test
    void usesTheSettingsOfItsTask() {
        LlmCallScope scope = new LlmCallScope();
        TokenUsageRecord usage = new TokenUsageRecord();
        scope.start(usage, taskSettings("qwen3.8-27b"));
        TokenUsageRecord global = new TokenUsageRecord();
        TokenUsageRecord.active = global;

        //The reasoning block is stripped from the answer.
        assertEquals("announcement", new AskingStrategy(unreachableYaml(), scope).ask());

        JsonObject body = requests.get(0);
        assertEquals("qwen3.8-27b", body.getString("model"));
        assertEquals(4096, body.getInteger("max_tokens"));
        assertFalse(body.containsKey("max_completion_tokens"));
        assertEquals(1.0, body.getDouble("temperature"));
        assertEquals(0.95, body.getDouble("top_p"));
        assertEquals(20, body.getInteger("top_k"));

        assertEquals(1, usage.llmCalls);
        assertEquals(57, usage.totalTokens);
        assertEquals(0, global.llmCalls, "a task's calls are not counted in the process-wide record");
    }

    @Test
    void tasksRunningAtTheSameTimeKeepTheirOwnSettingsAndUsage() {
        LlmCallScope first = new LlmCallScope();
        LlmCallScope second = new LlmCallScope();
        TokenUsageRecord firstUsage = new TokenUsageRecord();
        TokenUsageRecord secondUsage = new TokenUsageRecord();
        first.start(firstUsage, taskSettings("first-model"));
        second.start(secondUsage, taskSettings("second-model"));

        AskingStrategy firstStrategy = new AskingStrategy(unreachableYaml(), first);
        AskingStrategy secondStrategy = new AskingStrategy(unreachableYaml(), second);
        firstStrategy.ask();
        secondStrategy.ask();
        secondStrategy.ask();

        assertEquals(List.of("first-model", "second-model", "second-model"), requests.stream().map(body->body.getString("model")).toList());
        assertEquals(1, firstUsage.llmCalls);
        assertEquals(2, secondUsage.llmCalls);
    }

    @Test
    void callsAfterTheTaskEndsAreNotCountedAndUseTheServiceSettings() {
        LlmCallScope scope = new LlmCallScope();
        TokenUsageRecord usage = new TokenUsageRecord();
        scope.start(usage, taskSettings("qwen3.8-27b"));
        scope.stopCounting();
        scope.clearConfig();

        new AskingStrategy(new JsonObject()
                .put("secretKey", "yaml-key")
                .put("model", "yaml-model")
                .put("baseUrl", serverUrl()), scope).ask();

        assertEquals("yaml-model", requests.get(0).getString("model"));
        assertEquals(0, usage.llmCalls);
    }

    @Test
    void aServiceNotBuiltForATaskUsesItsOwnSettingsAndTheProcessWideRecord() {
        TokenUsageRecord global = new TokenUsageRecord();
        TokenUsageRecord.active = global;
        //A task running at the same time does not affect it.
        new LlmCallScope().start(new TokenUsageRecord(), taskSettings("qwen3.8-27b"));

        LinkLabelingServiceImpl labeler = new LinkLabelingServiceImpl(new JsonObject().put("openAI", new JsonObject()
                .put("secretKey", "yaml-key")
                .put("model", "yaml-model")
                .put("baseUrl", serverUrl())
                .putNull("temperature")
                .put("generateLinkLabel", new JsonObject().put("systemPrompt", "Label the link."))));

        JsonObject label = labeler.labelLink("/courses/2/announcements/17", "/courses/*/announcements/*").result();

        assertEquals("announcement", label.getString("type"));
        JsonObject body = requests.get(0);
        assertEquals("yaml-model", body.getString("model"));
        assertFalse(body.containsKey("temperature"));
        assertFalse(body.containsKey("top_k"));
        assertFalse(body.containsKey("max_tokens"));
        assertEquals(1, global.llmCalls);
    }
}
