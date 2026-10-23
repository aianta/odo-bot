package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.common.LlmCallScope;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls an OpenAI-compatible chat completions endpoint (llama.cpp, vLLM, SGLang, OpenRouter, ...). Port of
 * {@code mm_agents/qwen/client.py}.
 *
 * <p>Qwen3.8 servers return the chain of thought in a separate field: {@code reasoning_content} (llama.cpp, vLLM) or
 * {@code reasoning} (OpenRouter). It is merged back into the text as a {@code <think>} block, which the agent strips
 * before parsing.</p>
 *
 * <p>Every completion's reasoning is checked for completeness, see {@link #checkReasoning}. With
 * {@code requireFullReasoning}, an incomplete one is retried like a failed call, and fails the call once the attempts
 * run out; otherwise it is only logged.</p>
 */
public class OpenAIQwenModelClient implements QwenModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIQwenModelClient.class);

    /**
     * Raw reasoning runs about 3-4 characters per token. Less than this many characters per reported reasoning token
     * means the text is a summary or was cut.
     */
    static final double MIN_REASONING_CHARS_PER_TOKEN = 2.0;

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);

    private final QwenAgentConfig config;

    /**
     * The task this client makes calls for, or null to report them to {@link TokenUsageRecord#active}.
     */
    private final LlmCallScope scope;

    /**
     * Whether a completion whose reasoning did not come back in full fails the call.
     */
    private final boolean requireFullReasoning;

    private OpenAIClientAsync client;

    public OpenAIQwenModelClient(QwenAgentConfig config) {
        this(config, null);
    }

    /**
     * @param scope the task this client makes calls for, or null to report them to {@link TokenUsageRecord#active}.
     */
    public OpenAIQwenModelClient(QwenAgentConfig config, LlmCallScope scope) {
        this(config, scope, false);
    }

    /**
     * @param requireFullReasoning whether a completion whose reasoning did not come back in full fails the call.
     */
    public OpenAIQwenModelClient(QwenAgentConfig config, LlmCallScope scope, boolean requireFullReasoning) {
        this.config = config;
        this.scope = scope;
        this.requireFullReasoning = requireFullReasoning;
    }

    /**
     * The model's reasoning did not come back in full, and the task requires it.
     */
    public static class IncompleteReasoningException extends RuntimeException {
        public IncompleteReasoningException(String message) {
            super(message);
        }
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
        //The span covers every attempt and the backoff between them, since the agent waits on inference throughout.
        TokenUsageRecord.InferenceSpan span = scope == null ? TokenUsageRecord.begin(LlmCallType.UNCHARTED_STEP) : scope.begin(LlmCallType.UNCHARTED_STEP);
        attempt(context, params, 1, span, promise);
        return promise.future();
    }

    private void attempt(Context context, ChatCompletionCreateParams params, int attempt,
                         TokenUsageRecord.InferenceSpan span, Promise<QwenCompletion> promise) {
        Future.fromCompletionStage(client().chat().completions().create(params), context)
                .onSuccess(completion -> {
                    reportUsage(completion);
                    QwenCompletion qwenCompletion;
                    try {
                        qwenCompletion = toQwenCompletion(completion);
                    } catch (RuntimeException e) {
                        span.end();
                        promise.tryFail(e);
                        return;
                    }
                    ReasoningInfo reasoning = qwenCompletion.reasoning();
                    if (reasoning != null && !reasoning.complete()) {
                        String problem = "incomplete reasoning from %s: %s".formatted(
                                reasoning.provider() == null ? "the server" : reasoning.provider(), reasoning.problem());
                        if (requireFullReasoning) {
                            if (attempt >= config.client().maxAttempts()) {
                                span.fail();
                                promise.tryFail(new IncompleteReasoningException(problem));
                                return;
                            }
                            span.attemptFailed();
                            log.warn("[Qwen38Agent] {}, attempt {}/{}", problem, attempt, config.client().maxAttempts());
                            long delayMs = (long) (Math.min(5.0 * attempt, 30.0) * 1000);
                            context.owner().setTimer(delayMs, id -> attempt(context, params, attempt + 1, span, promise));
                            return;
                        }
                        log.warn("[Qwen38Agent] {}", problem);
                    }
                    span.end();
                    promise.tryComplete(qwenCompletion);
                })
                .onFailure(err -> {
                    Throwable cause = unwrap(err);
                    if (!isRetryable(cause) || attempt >= config.client().maxAttempts()) {
                        span.fail();
                        promise.tryFail(cause);
                        return;
                    }
                    span.attemptFailed();
                    log.warn("[Qwen38Agent] call_llm failed attempt {}/{}: {}", attempt, config.client().maxAttempts(), cause.toString());
                    long delayMs = (long) (Math.min(5.0 * attempt, 30.0) * 1000);
                    context.owner().setTimer(delayMs, id -> attempt(context, params, attempt + 1, span, promise));
                });
    }

    /**
     * Token accounting must never fail a step, so a missing or malformed usage block is only logged.
     */
    private void reportUsage(ChatCompletion completion) {
        try {
            completion.usage().ifPresentOrElse(
                    usage -> {
                        if (scope == null) {
                            TokenUsageRecord.report(LlmCallType.UNCHARTED_STEP, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                        } else {
                            scope.report(LlmCallType.UNCHARTED_STEP, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                        }
                    },
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
                    .baseUrl(config.client().baseUrl())
                    .apiKey(config.client().resolveApiKey(null))
                    .timeout(config.client().timeout())
                    // Retries are handled in attempt(), with OSWorld's schedule.
                    .maxRetries(0)
                    .build();
        }
        return client;
    }

    /**
     * @return whether a completion whose reasoning did not come back in full fails the call.
     */
    public boolean requiresFullReasoning() {
        return requireFullReasoning;
    }

    ChatCompletionCreateParams buildParams(JsonArray messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .messages(toMessageParams(messages));
        return config.client().withReasoningRequested().applyTo(builder).build();
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
        Map<String, JsonValue> properties = message._additionalProperties();

        //OpenRouter's field first; reasoning_content is the alias local servers use.
        String separate = nonBlank(stringProperty(properties, "reasoning"));
        String source = separate != null ? "reasoning" : null;
        if (separate == null) {
            separate = nonBlank(stringProperty(properties, "reasoning_content"));
            source = separate != null ? "reasoning_content" : null;
        }
        String reasoningText = separate;
        if (reasoningText == null) {
            Matcher inline = THINK_BLOCK.matcher(content);
            if (inline.find() && !inline.group(1).isBlank()) {
                reasoningText = inline.group(1).strip();
                source = "inline_think";
            }
        }

        Long promptTokens = null;
        Long completionTokens = null;
        Long reasoningTokens = null;
        try {
            if (completion.usage().isPresent()) {
                promptTokens = completion.usage().get().promptTokens();
                completionTokens = completion.usage().get().completionTokens();
                reasoningTokens = completion.usage().get().completionTokensDetails()
                        .flatMap(details -> details.reasoningTokens()).orElse(null);
            }
        } catch (RuntimeException e) {
            log.warn("[Qwen38Agent] could not read completion usage: {}", e.toString());
        }

        List<String> formats = reasoningFormats(properties.get("reasoning_details"));
        String provider = stringProperty(completion._additionalProperties(), "provider");
        String text = reasoningText == null ? "" : reasoningText.strip();
        ReasoningInfo reasoning = new ReasoningInfo(text, source, formats, reasoningTokens, promptTokens,
                completionTokens, provider, checkReasoning(text, formats, reasoningTokens));

        return new QwenCompletion(
                "inline_think".equals(source) ? content : mergeReasoningContent(content, separate),
                safeId(completion),
                stringProperty(completion._additionalProperties(), "hidden_states_path"),
                reasoning);
    }

    /**
     * Whether the reasoning came back in full: as raw text only (no summary or encrypted parts), and about as long as the
     * reasoning tokens the server reported.
     *
     * @return why it is not complete, or null if it is.
     */
    static String checkReasoning(String text, List<String> formats, Long reasoningTokens) {
        List<String> notText = formats.stream().filter(type -> !"reasoning.text".equals(type)).distinct().toList();
        if (!notText.isEmpty()) {
            return "reasoning came back as " + String.join(", ", notText);
        }
        if (text.isEmpty()) {
            return reasoningTokens != null && reasoningTokens > 0
                    ? "no reasoning text for %d reasoning tokens".formatted(reasoningTokens)
                    : "no reasoning came back";
        }
        if (reasoningTokens != null && reasoningTokens > 0 && text.length() < MIN_REASONING_CHARS_PER_TOKEN * reasoningTokens) {
            return "%d characters of reasoning for %d reasoning tokens, a summary or cut".formatted(text.length(), reasoningTokens);
        }
        return null;
    }

    /**
     * @return the {@code type} of each part of OpenRouter's {@code reasoning_details}, in order; empty if there are none.
     */
    private static List<String> reasoningFormats(JsonValue details) {
        if (details == null) {
            return List.of();
        }
        List<String> formats = new ArrayList<>();
        Object parts;
        try {
            parts = details.convert(Object.class);
        } catch (RuntimeException e) {
            log.warn("[Qwen38Agent] could not read reasoning_details: {}", e.toString());
            return formats;
        }
        if (parts instanceof List<?> list) {
            for (Object part : list) {
                if (part instanceof Map<?, ?> fields && fields.get("type") instanceof String type) {
                    formats.add(type);
                }
            }
        }
        return formats;
    }

    private static String nonBlank(String text) {
        return text == null || text.isBlank() ? null : text;
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
