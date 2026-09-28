package ca.ualberta.odobot.guidance.instructions;

import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/**
 * An instruction whose EXECUTION_RESULT is used to produce a follow-up instruction. For example, a
 * {@link QueryDom} returns candidate elements, from which the follow-up logic picks one to click.
 *
 * The harness holds the EXECUTION_RESULT promise and calls {@link #onExecutionResult(JsonObject, JsonObject, Consumer)}
 * when it arrives. The agent that emitted the instruction never sees the result; it observes the side effect
 * of the follow-up instead.
 */
public interface MultiStepInstruction {

    /**
     * The agent calls this before emitting the instruction. It gives the follow-up logic everything it needs.
     */
    void bind(FollowUpContext context);

    /**
     * The harness calls this when OdoX returns the EXECUTION_RESULT for this instruction.
     *
     * @param sent the enveloped EXECUTE message that was sent to OdoX.
     * @param result the EXECUTION_RESULT message.
     * @param next receives each follow-up instruction, there may be none.
     */
    void onExecutionResult(JsonObject sent, JsonObject result, Consumer<Instruction> next);

}
