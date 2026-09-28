package ca.ualberta.odobot.guidance.instructions;

/**
 * Emitted by an agent when it believes the task is done. Never sent to OdoX.
 */
public class TaskComplete extends Instruction{

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TaskComplete;
    }

    @Override
    public int hashCode() {
        return 1;
    }

    @Override
    public String toString() {
        return "Task Complete";
    }
}
