package ca.ualberta.odobot.guidance.instructions.uncharted;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * The action an uncharted agent chose in one model step. A step holds exactly one action, so every step yields exactly one
 * observation of its result.
 *
 * <p>The agent emits one step per observation and expects no execution result. The action changes the application, and the
 * harness observes the result: once the page has settled it takes a screenshot, see
 * {@link ca.ualberta.odobot.guidance.UnchartedStepObserver}.</p>
 */
public class UnchartedStep extends Instruction {

    /**
     * 1-based step number within the task.
     */
    public final int step;

    /**
     * The model's one-sentence description of the step, e.g. "Click the Save button".
     */
    public final String lowLevelInstruction;

    public final UnchartedInstruction action;

    public UnchartedStep(int step, String lowLevelInstruction, UnchartedInstruction action) {
        this.step = step;
        this.lowLevelInstruction = lowLevelInstruction;
        this.action = Objects.requireNonNull(action, "An uncharted step needs an action");
    }

    /**
     * The action is under {@code unchartedAction}, since {@code action} is the instruction type OdoX dispatches on.
     */
    @Override
    public JsonObject toJson() {
        return super.toJson()
                .put("action", "uncharted_step")
                .put("step", step)
                .put("lowLevelInstruction", lowLevelInstruction)
                .put("unchartedAction", action.toJson());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UnchartedStep other
                && step == other.step
                && Objects.equals(lowLevelInstruction, other.lowLevelInstruction)
                && action.equals(other.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, lowLevelInstruction, action);
    }

    @Override
    public String toString() {
        return "Uncharted step %d (%s): %s".formatted(step, lowLevelInstruction, action);
    }
}
