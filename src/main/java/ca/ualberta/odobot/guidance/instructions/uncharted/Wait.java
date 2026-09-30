package ca.ualberta.odobot.guidance.instructions.uncharted;

import io.vertx.core.json.JsonObject;

/**
 * Do nothing and let the page settle. {@link #seconds} is the duration the model asked for, or null, in which case
 * the executor uses its default. The parser also turns Qwen's {@code screenshot} action into a Wait, and the Qwen
 * agent sends a Wait for a step whose actions were all unsupported, so that the next observation shows the page
 * again.
 */
public class Wait extends UnchartedInstruction {

    public final Double seconds;

    public Wait(Double seconds) {
        this.seconds = seconds;
    }

    @Override
    public String action() {
        return "wait";
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = super.toJson();
        if (seconds != null) {
            json.put("seconds", seconds);
        }
        return json;
    }
}
