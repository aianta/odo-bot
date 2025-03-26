package ca.ualberta.odobot.explorer;

public enum Agent {

    ODO_BOT("odoBot"), ODO_BOT_NL("odoBotNL"), WEB_VOYAGER("webVoyager");

    final String taskField;

    Agent(String taskField){
        this.taskField = taskField;
    }

    public static Agent fromField(String field){
        return switch (field){
            case "odoBot" -> ODO_BOT;
            case "odoBotNL" -> ODO_BOT_NL;
            case "webVoyager"->WEB_VOYAGER;
            default -> throw new RuntimeException("Unknown agent value");
        };
    }


}
