package ca.ualberta.odobot.guidance.instructions;

import java.util.Objects;

/**
 * Emitted by an agent when it cannot continue. Never sent to OdoX.
 */
public class GiveUp extends Instruction{

    public final String reason;

    /**
     * True if the agent judged the task infeasible, as opposed to failing (an error, no path, ...). WebArena scores an
     * infeasible task's answer, "N/A" by default.
     */
    public final boolean infeasible;

    /**
     * The answer the agent reported with an infeasible task, e.g. why it can't be done, or null.
     */
    public final String answer;

    public GiveUp(String reason){
        this(reason, false, null);
    }

    private GiveUp(String reason, boolean infeasible, String answer){
        this.reason = reason;
        this.infeasible = infeasible;
        this.answer = answer;
    }

    /**
     * The agent judged the task infeasible.
     *
     * @param answer what the agent reported, or null.
     */
    public static GiveUp infeasible(String reason, String answer){
        return new GiveUp(reason, true, answer);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GiveUp other && Objects.equals(reason, other.reason) && infeasible == other.infeasible
                && Objects.equals(answer, other.answer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reason, infeasible, answer);
    }

    @Override
    public String toString() {
        return "Give Up: " + reason + (answer == null? "" : ", answer: " + answer);
    }
}
