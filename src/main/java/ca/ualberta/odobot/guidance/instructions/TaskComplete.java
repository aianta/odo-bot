package ca.ualberta.odobot.guidance.instructions;

import java.util.Objects;

/**
 * Emitted by an agent when it believes the task is done. Never sent to OdoX.
 */
public class TaskComplete extends Instruction{

    /**
     * The answer the agent reports, for tasks that ask for information, or null.
     */
    public final String answer;

    public TaskComplete(){
        this(null);
    }

    public TaskComplete(String answer){
        this.answer = answer;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TaskComplete other && Objects.equals(answer, other.answer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(TaskComplete.class, answer);
    }

    @Override
    public String toString() {
        return answer == null? "Task Complete" : "Task Complete, answer: " + answer;
    }
}
