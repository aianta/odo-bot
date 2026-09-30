package ca.ualberta.odobot.guidance.instructions.uncharted;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import io.vertx.core.json.JsonObject;

/**
 * A single low-level GUI action produced by an uncharted agent, e.g. a click at a screenshot coordinate or a
 * key press. Uncharted instructions are not sent on their own; an agent groups the actions of one model step
 * into an {@link UnchartedStep}.
 *
 * <p>Equality and hashing are derived from {@link #toJson()}, so subclasses only describe their fields there.</p>
 */
public abstract class UnchartedInstruction extends Instruction {

    /**
     * @return the action name OdoX dispatches on, e.g. {@code left_click}.
     */
    public abstract String action();

    @Override
    public JsonObject toJson() {
        return super.toJson().put("action", action());
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == getClass() && toJson().equals(((UnchartedInstruction) obj).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + toJson().encode();
    }
}
