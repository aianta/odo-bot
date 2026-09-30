package ca.ualberta.odobot.guidance.uncharted;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Conversation assembly and screenshot folding, ported from {@code mm_agents/qwen/history.py}.
 *
 * <p>Messages are built in the OpenAI chat wire format (the same dicts the Python agent sends), so they can be
 * dumped for debugging as is and converted to SDK types in one place ({@link OpenAIQwenModelClient}).</p>
 */
public final class QwenHistory {

    private QwenHistory() {
    }

    /**
     * Once more than {@code imageMax} screenshots would be shown, collapse the oldest ones {@code foldSize} at a
     * time.
     *
     * @return the number of leading steps whose screenshots are collapsed.
     */
    public static int updateFoldingState(int totalScreenshots, int foldedPrefixK, int imageMax, int foldSize) {
        while ((totalScreenshots - foldedPrefixK) > imageMax) {
            foldedPrefixK += foldSize;
        }
        return Math.min(foldedPrefixK, totalScreenshots);
    }

    static boolean shouldCollapseStep(int stepNum1Based, int foldedPrefixK) {
        return stepNum1Based <= foldedPrefixK;
    }

    /**
     * The low-level instructions of the steps before {@code startStep}, which are no longer replayed as turns.
     */
    public static String previousActionsText(List<String> actions, int startStep) {
        int count = Math.min(startStep - 1, actions.size());
        if (count <= 0) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("Step ").append(i + 1).append(": ").append(actions.get(i));
        }
        return sb.toString();
    }

    static JsonArray wrapToolResponse(JsonArray parts) {
        JsonArray wrapped = new JsonArray().add(textPart("<tool_response>\n"));
        parts.forEach(wrapped::add);
        return wrapped.add(textPart("\n</tool_response>"));
    }

    public static JsonArray buildMessages(String systemPrompt, String instructionPrompt, List<String> screenshots,
                                          List<String> responses, int startStep, int totalSteps, int foldedPrefixK,
                                          String collapseText) {
        return buildMessages(systemPrompt, instructionPrompt, screenshots, responses, startStep, totalSteps,
                foldedPrefixK, collapseText, UnaryOperator.identity());
    }

    /**
     * @param screenshots base64 PNGs, one per step.
     * @param responses   the model's answers with reasoning stripped, one per completed step.
     */
    public static JsonArray buildMessages(String systemPrompt, String instructionPrompt, List<String> screenshots,
                                          List<String> responses, int startStep, int totalSteps, int foldedPrefixK,
                                          String collapseText, UnaryOperator<String> responseTransform) {
        JsonArray messages = new JsonArray()
                .add(message("system", new JsonArray().add(textPart(systemPrompt))));

        for (int stepNum = startStep; stepNum <= totalSteps; stepNum++) {
            boolean isFirstTurn = stepNum == startStep;
            JsonArray userContent;

            if (shouldCollapseStep(stepNum, foldedPrefixK)) {
                userContent = isFirstTurn
                        ? new JsonArray().add(textPart(instructionPrompt))
                        : wrapToolResponse(new JsonArray().add(textPart(collapseText)));
            } else {
                JsonObject image = imagePart("data:image/png;base64," + screenshots.get(stepNum - 1));
                userContent = isFirstTurn
                        ? new JsonArray().add(image).add(textPart(instructionPrompt))
                        : wrapToolResponse(new JsonArray().add(image));
            }
            messages.add(message("user", userContent));

            if (stepNum <= totalSteps - 1 && (stepNum - 1) < responses.size()) {
                messages.add(message("assistant",
                        new JsonArray().add(textPart(responseTransform.apply(responses.get(stepNum - 1))))));
            }
        }
        return messages;
    }

    /**
     * Copy of the messages with inline images truncated, for debug dumps.
     */
    public static JsonArray sanitizeForDump(JsonArray messages) {
        JsonArray sanitized = new JsonArray();
        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = messages.getJsonObject(i);
            JsonArray content = new JsonArray();
            JsonArray parts = message.getJsonArray("content", new JsonArray());
            for (int j = 0; j < parts.size(); j++) {
                JsonObject part = parts.getJsonObject(j);
                String url = "image_url".equals(part.getString("type"))
                        ? part.getJsonObject("image_url", new JsonObject()).getString("url", "")
                        : null;
                if (url != null && url.startsWith("data:image/")) {
                    content.add(imagePart(url.substring(0, Math.min(40, url.length())) + "...<omitted>"));
                } else {
                    content.add(part);
                }
            }
            sanitized.add(new JsonObject().put("role", message.getString("role")).put("content", content));
        }
        return sanitized;
    }

    static JsonObject message(String role, JsonArray content) {
        return new JsonObject().put("role", role).put("content", content);
    }

    static JsonObject textPart(String text) {
        return new JsonObject().put("type", "text").put("text", text);
    }

    static JsonObject imagePart(String url) {
        return new JsonObject().put("type", "image_url").put("image_url", new JsonObject().put("url", url));
    }
}
