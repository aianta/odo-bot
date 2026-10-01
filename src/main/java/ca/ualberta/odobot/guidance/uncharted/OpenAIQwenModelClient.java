package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.common.LlmCallType;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.chat.completions.*;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Calls an OpenAI-compatible chat completions endpoint (llama.cpp, vLLM, SGLang, ...). Port of
 * {@code mm_agents/qwen/client.py}.
 *
 * <p>Qwen3.8 servers return the chain of thought in a separate {@code reasoning_content} field. It is merged back
 * into the text as a {@code <think>} block, which the agent strips before parsing.</p>
 */
public class OpenAIQwenModelClient implements QwenModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIQwenModelClient.class);

    private final QwenAgentConfig config;

    private OpenAIClientAsync client;

    public OpenAIQwenModelClient(QwenAgentConfig config) {
        this.config = config;
    }

    @Override
    public Future<QwenCompletion> complete(Context context, JsonArray messages) {
        ChatCompletionCreateParams params;
        try {
            params = buildParams(messages);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Promise<QwenCompletion> promise = Promise.promise();
        attempt(context, params, 1, promise);
        return promise.future();
    }

    private void attempt(Context context, ChatCompletionCreateParams params, int attempt, Promise<QwenCompletion> promise) {
        Future.fromCompletionStage(client().chat().completions().create(params), context)
                .onSuccess(completion -> {
                    reportUsage(completion);
                    try {
                        promise.tryComplete(toQwenCompletion(completion));
                    } catch (RuntimeException e) {
                        promise.tryFail(e);
                    }
                })
                .onFailure(err -> {
                    Throwable cause = unwrap(err);
                    if (!isRetryable(cause) || attempt >= config.maxAttempts()) {
                        promise.tryFail(cause);
                        return;
                    }
                    log.warn("[Qwen38Agent] call_llm failed attempt {}/{}: {}", attempt, config.maxAttempts(), cause.toString());
                    long delayMs = (long) (Math.min(5.0 * attempt, 30.0) * 1000);
                    context.owner().setTimer(delayMs, id -> attempt(context, params, attempt + 1, promise));
                });
    }

    /**
     * Token accounting must never fail a step, so a missing or malformed usage block is only logged.
     */
    private static void reportUsage(ChatCompletion completion) {
        try {
            completion.usage().ifPresentOrElse(
                    usage -> TokenUsageRecord.report(LlmCallType.UNCHARTED_STEP,
                            usage.promptTokens(), usage.completionTokens(), usage.totalTokens()),
                    () -> log.warn("[Qwen38Agent] completion has no usage block, its tokens are not counted"));
        } catch (RuntimeException e) {
            log.warn("[Qwen38Agent] could not read completion usage: {}", e.toString());
        }
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private synchronized OpenAIClientAsync client() {
        if (client == null) {
            client = OpenAIOkHttpClientAsync.builder()
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .timeout(config.timeout())
                    // Retries are handled in attempt(), with OSWorld's schedule.
                    .maxRetries(0)
                    .build();
        }
        return client;
    }

    ChatCompletionCreateParams buildParams(JsonArray messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(config.model())
                .messages(toMessageParams(messages))
                .maxTokens(config.maxTokens())
                .temperature(config.temperature())
                .topP(config.topP());
        // Not part of the OpenAI schema; local servers read them straight off the request body.
        if (config.topK() != null) {
            builder.putAdditionalBodyProperty("top_k", JsonValue.from(config.topK()));
        }
        if (config.presencePenalty() != null) {
            builder.putAdditionalBodyProperty("presence_penalty", JsonValue.from(config.presencePenalty()));
        }
        return builder.build();
    }

    static List<ChatCompletionMessageParam> toMessageParams(JsonArray messages) {
        List<ChatCompletionMessageParam> params = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = messages.getJsonObject(i);
            JsonArray content = message.getJsonArray("content");
            String role = message.getString("role");
            switch (role) {
                case "system" -> params.add(ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                                .contentOfArrayOfContentParts(textParts(content))
                                .build()));
                case "assistant" -> params.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder()
                                .contentOfArrayOfContentParts(textParts(content).stream()
                                        .map(ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart::ofText)
                                        .toList())
                                .build()));
                case "user" -> params.add(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .contentOfArrayOfContentParts(userParts(content))
                                .build()));
                default -> throw new IllegalArgumentException("Unsupported message role: " + role);
            }
        }
        return params;
    }

    private static List<ChatCompletionContentPartText> textParts(JsonArray content) {
        List<ChatCompletionContentPartText> parts = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            JsonObject part = content.getJsonObject(i);
            if (!"text".equals(part.getString("type"))) {
                throw new IllegalArgumentException("Only text parts are supported here, got: " + part.getString("type"));
            }
            parts.add(ChatCompletionContentPartText.builder().text(part.getString("text")).build());
        }
        return parts;
    }

    private static List<ChatCompletionContentPart> userParts(JsonArray content) {
        List<ChatCompletionContentPart> parts = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            JsonObject part = content.getJsonObject(i);
            String type = part.getString("type");
            switch (type) {
                case "text" -> parts.add(ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder().text(part.getString("text")).build()));
                case "image_url" -> parts.add(ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(part.getJsonObject("image_url").getString("url"))
                                        .build())
                                .build()));
                default -> throw new IllegalArgumentException("Unsupported content part type: " + type);
            }
        }
        return parts;
    }

    static QwenCompletion toQwenCompletion(ChatCompletion completion) {
        ChatCompletionMessage message = completion.choices().get(0).message();
        String content = message.content().orElse("");
        String reasoning = stringProperty(message._additionalProperties(), "reasoning_content");
        return new QwenCompletion(
                mergeReasoningContent(content, reasoning),
                safeId(completion),
                stringProperty(completion._additionalProperties(), "hidden_states_path"));
    }

    static String mergeReasoningContent(String content, String reasoning) {
        String contentText = content == null ? "" : content;
        String reasoningText = reasoning == null ? "" : reasoning.strip();
        if (reasoningText.isEmpty()) {
            return contentText;
        }
        return "<think>\n" + reasoningText + "\n</think>\n\n" + contentText.stripLeading();
    }

    private static String safeId(ChatCompletion completion) {
        try {
            return completion.id();
        } catch (RuntimeException e) {
            // Some local servers omit the id.
            return null;
        }
    }

    private static String stringProperty(Map<String, JsonValue> properties, String name) {
        JsonValue value = properties.get(name);
        if (value == null) {
            return null;
        }
        Optional<String> text = value.asString();
        return text.orElse(null);
    }

    private static Throwable unwrap(Throwable err) {
        Throwable cause = err;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * The failures OSWorld retries: connection and timeout errors, rate limits, bad requests and server errors.
     */
    private static boolean isRetryable(Throwable cause) {
        return cause instanceof OpenAIIoException
                || cause instanceof RateLimitException
                || cause instanceof BadRequestException
                || cause instanceof InternalServerException
                || cause instanceof IOException
                || cause instanceof TimeoutException;
    }
}
