package ca.ualberta.odobot.explorer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum Agent {


    ODO_BOT("odoBot"), ODO_BOT_NL("odoBotNL"), WEB_VOYAGER("webVoyager");

    final String taskField;

    Agent(String taskField){
        this.taskField = taskField;
    }

    public static boolean isValidAgent(String value){
        return Arrays.stream(Agent.values()).map(agent->agent.taskField.toLowerCase())
                .anyMatch(agentString->value.toLowerCase().contains(agentString));
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
