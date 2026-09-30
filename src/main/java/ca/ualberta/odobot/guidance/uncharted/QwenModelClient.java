package ca.ualberta.odobot.guidance.uncharted;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;

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
     */
    record QwenCompletion(String text, String requestId, String hiddenStatesPath) {
    }
}
