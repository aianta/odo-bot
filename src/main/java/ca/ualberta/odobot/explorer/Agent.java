package ca.ualberta.odobot.explorer;

public enum Agent {

    ODO_BOT("odoBot"), WEB_VOYAGER("webVoyager");

    String taskField;

    Agent(String taskField){
        this.taskField = taskField;
    }



}
