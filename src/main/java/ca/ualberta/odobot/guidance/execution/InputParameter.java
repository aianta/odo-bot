package ca.ualberta.odobot.guidance.execution;

public class InputParameter extends ExecutionParameter{

    private String value;

    public String getValue() {
        return value;
    }

    public InputParameter setValue(String value) {
        this.value = value;
        return this;
    }
}
