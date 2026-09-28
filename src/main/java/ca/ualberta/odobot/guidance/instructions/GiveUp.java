package ca.ualberta.odobot.guidance.instructions;

import java.util.Objects;

/**
 * Emitted by an agent when it cannot continue. Never sent to OdoX.
 */
public class GiveUp extends Instruction{

    public final String reason;

    public GiveUp(String reason){
        this.reason = reason;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GiveUp other && Objects.equals(reason, other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reason);
    }

    @Override
    public String toString() {
        return "Give Up: " + reason;
    }
}
