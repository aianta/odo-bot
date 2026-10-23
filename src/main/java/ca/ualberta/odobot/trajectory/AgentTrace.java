package ca.ualberta.odobot.trajectory;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * What an uncharted agent thought in one model step, attached to the instruction the step emitted (see
 * {@link ca.ualberta.odobot.guidance.instructions.Instruction#getAgentTrace()}), so the harness can save it with the
 * task's trajectory. It never reaches OdoX.
 *
 * @param stepNumber          1-based step number within the task.
 * @param inputObservation    the observation the model reasoned over.
 * @param rawResponse         the model's answer, reasoning included as a leading {@code <think>} block.
 * @param reasoning           the reasoning text, or "" if none came back.
 * @param committedResponse   the answer without its reasoning, as replayed in the conversation.
 * @param lowLevelInstruction the model's one-sentence description of the step.
 * @param action              the action executed, as sent to OdoX, or null if the step executes none.
 * @param pyAutoGui           the step as OSWorld's pyautogui code.
 * @param unsupported         actions the model asked for that were not executed, with why.
 * @param terminal            NONE, DONE or FAIL.
 * @param answer              the answer the model gave when it ended the task, or null.
 * @param promptMessages      the conversation sent to the model, images omitted.
 * @param requestId           the completion id, or null.
 * @param promptTokens        null if the server reported no usage.
 * @param completionTokens    null if the server reported no usage.
 * @param reasoningTokens     null if the server reported none.
 * @param provider            the provider that served the completion (OpenRouter), or null.
 * @param reasoningSource     where the reasoning came from ({@code reasoning}, {@code reasoning_content} or
 *                            {@code inline_think}), or null.
 * @param reasoningFormats    the types of the completion's {@code reasoning_details}.
 * @param reasoningProblem    why the reasoning is not complete, or null if it is.
 * @param originalWidth       width of the screenshot the model was shown, before resizing.
 * @param originalHeight      height of that screenshot, before resizing.
 * @param processedWidth      width of the resized screenshot sent to the model.
 * @param processedHeight     height of the resized screenshot sent to the model.
 * @param answeredAt          epoch ms when the model answered.
 */
public record AgentTrace(
        int stepNumber,
        TimelineEntity inputObservation,
        String rawResponse,
        String reasoning,
        String committedResponse,
        String lowLevelInstruction,
        JsonObject action,
        List<String> pyAutoGui,
        List<String> unsupported,
        String terminal,
        String answer,
        JsonArray promptMessages,
        String requestId,
        Long promptTokens,
        Long completionTokens,
        Long reasoningTokens,
        String provider,
        String reasoningSource,
        List<String> reasoningFormats,
        String reasoningProblem,
        int originalWidth,
        int originalHeight,
        int processedWidth,
        int processedHeight,
        long answeredAt
) {

    public AgentTrace {
        pyAutoGui = pyAutoGui == null ? List.of() : List.copyOf(pyAutoGui);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        reasoningFormats = reasoningFormats == null ? List.of() : List.copyOf(reasoningFormats);
        reasoning = reasoning == null ? "" : reasoning;
    }

    /**
     * @return true if the full-reasoning check passed.
     */
    public boolean reasoningComplete() {
        return reasoningProblem == null;
    }
}
