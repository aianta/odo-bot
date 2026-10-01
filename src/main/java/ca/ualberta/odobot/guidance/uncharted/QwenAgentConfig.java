package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.common.LlmClientConfig;
import io.vertx.core.json.JsonObject;

/**
 * Settings for {@link Qwen38Agent}. The defaults are those of OSWorld's {@code run_multienv_qwen3_8.py}.
 *
 * @param client          the OpenAI client settings, shared with the charted agent.
 * @param historyN        number of most recent steps replayed as conversation turns; older steps only appear in
 *                        the "Previous actions" text.
 * @param imageMax        maximum number of screenshots kept in the conversation before older ones are collapsed.
 * @param foldSize        number of screenshots collapsed at a time once {@code imageMax} is exceeded.
 * @param coordinateType  RELATIVE: the model answers on a 1000x1000 grid. ABSOLUTE: in resized-screenshot pixels.
 * @param collapseText    text that replaces a collapsed screenshot.
 */
public record QwenAgentConfig(
        LlmClientConfig client,
        int historyN,
        int imageMax,
        int foldSize,
        CoordinateType coordinateType,
        String collapseText
) {

    public enum CoordinateType { RELATIVE, ABSOLUTE }

    public static final String DEFAULT_COLLAPSE_TEXT = "This screenshot has been collapsed.";

    public QwenAgentConfig {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (coordinateType == null) {
            throw new IllegalArgumentException("coordinateType is required");
        }
        if (imageMax < 1) {
            throw new IllegalArgumentException("imageMax must be >= 1");
        }
        if (foldSize < 1) {
            throw new IllegalArgumentException("foldSize must be >= 1");
        }
        if (historyN < 0) {
            throw new IllegalArgumentException("historyN must be >= 0");
        }
        if (collapseText == null) {
            collapseText = DEFAULT_COLLAPSE_TEXT;
        }
    }

    public static QwenAgentConfig defaults() {
        return fromJson(new JsonObject());
    }

    /**
     * @see #fromJson(JsonObject, JsonObject)
     */
    public static QwenAgentConfig fromJson(JsonObject qwen) {
        return fromJson(qwen, new JsonObject());
    }

    /**
     * Agent keys mirror the command line flags of {@code run_multienv_qwen3_8.py}: {@code history_n},
     * {@code image_max}, {@code fold_size}, {@code coord} ("relative" or "absolute") and {@code collapse_text}.
     * The client settings come from {@code llm} (see {@link LlmClientConfig#fromJson}). For older task files, client
     * keys found in {@code qwen} are used when {@code llm} does not set them. Missing keys take the defaults.
     */
    public static QwenAgentConfig fromJson(JsonObject qwen, JsonObject llm) {
        JsonObject clientJson = qwen.copy().mergeIn(llm);
        return new QwenAgentConfig(
                LlmClientConfig.fromJson(clientJson),
                qwen.getInteger("history_n", 5),
                qwen.getInteger("image_max", 5),
                qwen.getInteger("fold_size", 2),
                CoordinateType.valueOf(qwen.getString("coord", "relative").toUpperCase()),
                qwen.getString("collapse_text", DEFAULT_COLLAPSE_TEXT)
        );
    }
}
