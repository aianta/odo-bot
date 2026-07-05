package ca.ualberta.odobot.guidance.instructions;

public class NoOp extends Instruction{


    @Override
    public boolean equals(Object obj) {
        return obj instanceof NoOp;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "No Operation";
    }
}
