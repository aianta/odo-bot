package ca.ualberta.odobot.guidance.uncharted;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;

import java.util.List;

/**
 * Sends a conversation to the model.
 */
public interface QwenModelClient {

    /**
     * @param context  the Vert.x context the returned future completes on.
     * @param messages chat messages in the OpenAI wire format, as built by {@link QwenHistory}.
     */
    Future<QwenCompletion> complete(Context context, JsonArray messages);

    /**
     * Release connections. The client can still be used afterwards.
     */
    default void close() {
    }

    /**
     * @param text             the answer, with any separately returned reasoning merged in as a leading
     *                         {@code <think>} block.
     * @param requestId        the completion id, if the server returned one.
     * @param hiddenStatesPath where the local server saved the hidden states for this request, if it did.
     * @param reasoning        what came back of the model's reasoning, or null if unknown.
     */
    record QwenCompletion(String text, String requestId, String hiddenStatesPath, ReasoningInfo reasoning) {

        public QwenCompletion(String text, String requestId, String hiddenStatesPath) {
            this(text, requestId, hiddenStatesPath, null);
        }
    }

    /**
     * The reasoning of one completion, and whether it came back in full.
     *
     * @param text             the reasoning text, or "" if none came back.
     * @param source           where it came from: {@code reasoning} (OpenRouter), {@code reasoning_content} (llama.cpp,
     *                         vLLM), {@code inline_think} (a {@code <think>} block in the content), or null if none.
     * @param formats          the types of the completion's {@code reasoning_details}, e.g. {@code reasoning.text}.
     * @param reasoningTokens  the usage's reasoning tokens, or null if the server did not report them.
     * @param promptTokens     null if the server reported no usage.
     * @param completionTokens null if the server reported no usage.
     * @param provider         the provider that served the completion (OpenRouter), or null.
     * @param problem          why the reasoning is not complete, or null if it is.
     */
    record ReasoningInfo(String text, String source, List<String> formats, Long reasoningTokens, Long promptTokens,
                         Long completionTokens, String provider, String problem) {

        public boolean complete() {
            return problem == null;
        }
    }
}
