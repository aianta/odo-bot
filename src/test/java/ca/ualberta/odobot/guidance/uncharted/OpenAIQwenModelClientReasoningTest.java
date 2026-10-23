package ca.ualberta.odobot.guidance.uncharted;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenRouter returns Qwen's reasoning in {@code reasoning} and {@code reasoning_details}, which reach the agent merged
 * as a {@code <think>} block, and whose completeness is checked on every completion.
 */
class OpenAIQwenModelClientReasoningTest {

    private static final String FULL_REASONING = "The Save button is at the top right, so I click it to store the product. ".repeat(4).strip();

    private Vertx vertx;
    private HttpServer server;

    /**
     * The messages the fake server answers with, in order; the last one is repeated.
     */
    private final ConcurrentLinkedQueue<JsonObject> answers = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startFakeServer() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            JsonObject message = answers.size() > 1 ? answers.poll() : answers.peek();
            request.response().putHeader("content-type", "application/json").end(completion(message).encode());
        })).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static JsonObject completion(JsonObject message) {
        return new JsonObject()
                .put("id", "gen-1")
                .put("object", "chat.completion")
                .put("created", 0)
                .put("model", "qwen/qwen3.8-27b")
                .put("provider", message.getString("_provider", "DeepInfra"))
                .put("usage", new JsonObject().put("prompt_tokens", 1200).put("completion_tokens", 120).put("total_tokens", 1320)
                        .put("completion_tokens_details", new JsonObject().put("reasoning_tokens", message.getInteger("_reasoningTokens", 60))))
                .put("choices", new JsonArray().add(new JsonObject()
                        .put("index", 0)
                        .put("finish_reason", "stop")
                        .put("message", message.copy().put("role", "assistant"))));
    }

    private static JsonObject openRouterMessage(String reasoning, String detailType) {
        JsonObject message = new JsonObject().put("content", "\n\nAction: Click Save.\n<tool_call>{}</tool_call>")
                .put("reasoning", reasoning);
        if (detailType != null) {
            message.put("reasoning_details", new JsonArray().add(new JsonObject().put("type", detailType).put("text", reasoning)));
        }
        return message;
    }

    private QwenCompletion complete(boolean requireFullReasoning, int maxAttempts) throws Exception {
        QwenAgentConfig config = QwenAgentConfig.fromJson(new JsonObject()
                .put("base_url", "http://127.0.0.1:%d/v1".formatted(server.actualPort()))
                .put("max_attempts", maxAttempts));
        OpenAIQwenModelClient client = new OpenAIQwenModelClient(config, null, requireFullReasoning);
        Context context = vertx.getOrCreateContext();
        CompletableFuture<QwenCompletion> result = new CompletableFuture<>();
        context.runOnContext(v -> client.complete(context, new JsonArray().add(QwenHistory.message("user",
                        new JsonArray().add(QwenHistory.textPart("INSTR")))))
                .onSuccess(result::complete).onFailure(result::completeExceptionally));
        try {
            return result.get(60, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    @Test
    void openRoutersReasoningFieldIsMergedAndRecorded() throws Exception {
        answers.add(openRouterMessage(FULL_REASONING, "reasoning.text"));

        QwenCompletion completion = complete(false, 1);

        assertEquals("<think>\n" + FULL_REASONING + "\n</think>\n\nAction: Click Save.\n<tool_call>{}</tool_call>", completion.text());
        assertEquals(FULL_REASONING, completion.reasoning().text());
        assertEquals("reasoning", completion.reasoning().source());
        assertEquals(List.of("reasoning.text"), completion.reasoning().formats());
        assertEquals(60L, completion.reasoning().reasoningTokens());
        assertEquals("DeepInfra", completion.reasoning().provider());
        assertTrue(completion.reasoning().complete());
    }

    @Test
    void reasoningInlineInTheContentIsFound() throws Exception {
        answers.add(new JsonObject().put("content", "<think>" + FULL_REASONING + "</think>\nAction: Click Save."));

        QwenCompletion completion = complete(false, 1);

        assertEquals("inline_think", completion.reasoning().source());
        assertEquals(FULL_REASONING, completion.reasoning().text());
        assertTrue(completion.text().startsWith("<think>"), "the content is passed on as it came");
    }

    @Test
    void incompleteReasoningIsOnlyLoggedWhenTheTaskDoesNotRequireIt() throws Exception {
        answers.add(openRouterMessage("Click save.", "reasoning.summary"));

        QwenCompletion completion = complete(false, 1);

        assertFalse(completion.reasoning().complete());
        assertEquals("reasoning came back as reasoning.summary", completion.reasoning().problem());
    }

    @Test
    void incompleteReasoningIsRetriedAndThenFailsWhenRequired() throws Exception {
        answers.add(openRouterMessage(null, null).put("_provider", "Chutes"));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> complete(true, 2));

        assertTrue(failure.getCause() instanceof OpenAIQwenModelClient.IncompleteReasoningException);
        assertEquals("incomplete reasoning from Chutes: no reasoning text for 60 reasoning tokens", failure.getCause().getMessage());
    }

    @Test
    void aRetryThatGetsFullReasoningSucceeds() throws Exception {
        answers.add(openRouterMessage("Short.", "reasoning.text").put("_provider", "Chutes"));
        answers.add(openRouterMessage(FULL_REASONING, "reasoning.text"));

        QwenCompletion completion = complete(true, 2);

        assertEquals("DeepInfra", completion.reasoning().provider());
        assertTrue(completion.reasoning().complete());
    }

    @Test
    void theCheckLooksAtFormatsPresenceAndLength() {
        assertNull(OpenAIQwenModelClient.checkReasoning(FULL_REASONING, List.of("reasoning.text"), 60L));
        assertNull(OpenAIQwenModelClient.checkReasoning("Some thoughts.", List.of(), null), "no token count, nothing to compare");
        assertEquals("reasoning came back as reasoning.encrypted",
                OpenAIQwenModelClient.checkReasoning(FULL_REASONING, List.of("reasoning.text", "reasoning.encrypted"), 60L));
        assertEquals("no reasoning came back", OpenAIQwenModelClient.checkReasoning("", List.of(), null));
        assertEquals("6 characters of reasoning for 60 reasoning tokens, a summary or cut",
                OpenAIQwenModelClient.checkReasoning("Short.", List.of(), 60L));
    }
}
