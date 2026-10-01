package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.TokenUsageRecord;
import ca.ualberta.odobot.guidance.uncharted.QwenModelClient.QwenCompletion;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIQwenModelClientTest {

    private Vertx vertx;
    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startFakeServer() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            requests.add(body.toJsonObject());
            if (calls.incrementAndGet() == 1) {
                request.response().setStatusCode(500).putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", new JsonObject().put("message", "warming up")).encode());
                return;
            }
            JsonObject completion = new JsonObject()
                    .put("id", "chatcmpl-42")
                    .put("object", "chat.completion")
                    .put("created", 0)
                    .put("model", "qwen3.8-27b")
                    .put("hidden_states_path", "/tmp/hidden/42.pt")
                    .put("usage", new JsonObject()
                            .put("prompt_tokens", 1200)
                            .put("completion_tokens", 80)
                            .put("total_tokens", 1280))
                    .put("choices", new JsonArray().add(new JsonObject()
                            .put("index", 0)
                            .put("finish_reason", "stop")
                            .put("message", new JsonObject()
                                    .put("role", "assistant")
                                    .put("content", "\n\nAction: Click.\n<tool_call>{}</tool_call>")
                                    .put("reasoning_content", "  I should click.  "))));
            request.response().putHeader("content-type", "application/json").end(completion.encode());
        })).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void sendsQwenParametersRetriesAndMergesReasoning() throws Exception {
        QwenAgentConfig config = QwenAgentConfig.fromJson(new JsonObject()
                .put("base_url", "http://127.0.0.1:%d/v1".formatted(server.actualPort())));
        OpenAIQwenModelClient client = new OpenAIQwenModelClient(config);

        JsonArray messages = new JsonArray()
                .add(QwenHistory.message("system", new JsonArray().add(QwenHistory.textPart("SYS"))))
                .add(QwenHistory.message("user", new JsonArray()
                        .add(QwenHistory.imagePart("data:image/png;base64,AAAA"))
                        .add(QwenHistory.textPart("INSTR"))))
                .add(QwenHistory.message("assistant", new JsonArray().add(QwenHistory.textPart("prev"))))
                .add(QwenHistory.message("user", new JsonArray().add(QwenHistory.textPart("<tool_response>\n"))));

        TokenUsageRecord usage = new TokenUsageRecord();
        TokenUsageRecord.active = usage;

        Context context = vertx.getOrCreateContext();
        CompletableFuture<QwenCompletion> result = new CompletableFuture<>();
        context.runOnContext(v -> client.complete(context, messages)
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally));

        // One 500, a 5 s back-off, then success.
        QwenCompletion completion;
        try {
            completion = result.get(30, TimeUnit.SECONDS);
        } finally {
            TokenUsageRecord.active = null;
        }
        client.close();

        assertEquals(2, calls.get());

        // Only the successful attempt has usage to count.
        JsonObject step = usage.toJson().getJsonObject("byCallType").getJsonObject("uncharted-step");
        assertEquals(1, step.getInteger("llmCalls"));
        assertEquals(1200, step.getInteger("inputTokens"));
        assertEquals(80, step.getInteger("outputTokens"));
        assertEquals(1280, step.getInteger("totalTokens"));
        assertEquals("chat_completion", step.getString("kind"));
        assertEquals("<think>\nI should click.\n</think>\n\nAction: Click.\n<tool_call>{}</tool_call>", completion.text());
        assertEquals("chatcmpl-42", completion.requestId());
        assertEquals("/tmp/hidden/42.pt", completion.hiddenStatesPath());

        JsonObject body = requests.get(1);
        assertEquals("qwen3.8-27b", body.getString("model"));
        assertEquals(4096, body.getInteger("max_tokens"));
        assertEquals(1.0, body.getDouble("temperature"));
        assertEquals(0.95, body.getDouble("top_p"));
        assertEquals(20, body.getInteger("top_k"));
        assertFalse(body.containsKey("presence_penalty"));

        JsonArray sent = body.getJsonArray("messages");
        assertEquals(List.of("system", "user", "assistant", "user"),
                sent.stream().map(m -> ((JsonObject) m).getString("role")).toList());
        JsonObject image = sent.getJsonObject(1).getJsonArray("content").getJsonObject(0);
        assertEquals("image_url", image.getString("type"));
        assertEquals("data:image/png;base64,AAAA", image.getJsonObject("image_url").getString("url"));
        assertEquals("prev", sent.getJsonObject(2).getJsonArray("content").getJsonObject(0).getString("text"));
    }

    @Test
    void reasoningIsOmittedWhenBlank() {
        assertEquals("answer", OpenAIQwenModelClient.mergeReasoningContent("answer", "   "));
        assertEquals("answer", OpenAIQwenModelClient.mergeReasoningContent("answer", null));
    }
}
