package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.guidance.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code agent} query parameter of {@code POST /api/evaluate}. It selects both the task format, the field of each task entry
 * that is read ({@link #taskField}), and how the tasks are executed ({@link #mode}).
 *
 * <table>
 *     <tr><th>{@code agent=}</th><th>task field</th><th>execution</th></tr>
 *     <tr><td>{@code odoBot}</td><td>{@code odoBot}</td><td>charted, tasks predefined in terms of the navigation model</td></tr>
 *     <tr><td>{@code odoBotNL}</td><td>{@code odoBotNL}</td><td>charted, natural language tasks (the CASCON 2026 evaluation)</td></tr>
 *     <tr><td>{@code uncharted}</td><td>{@code odoBotNL}</td><td>uncharted, the Qwen3.8 agent</td></tr>
 *     <tr><td>{@code hybrid}</td><td>{@code odoBotNL}</td><td>hybrid, not implemented yet</td></tr>
 *     <tr><td>{@code webVoyager}</td><td>{@code webVoyager}</td><td>not executed by OdoBot, used to evaluate baseline runs</td></tr>
 * </table>
 */
public enum Agent {


    ODO_BOT("odoBot", "odoBot", ExecutionMode.CHARTED),
    ODO_BOT_NL("odoBotNL", "odoBotNL", ExecutionMode.CHARTED),
    UNCHARTED("uncharted", "odoBotNL", ExecutionMode.UNCHARTED),
    HYBRID("hybrid", "odoBotNL", ExecutionMode.HYBRID),
    WEB_VOYAGER("webVoyager", "webVoyager", null);

    /**
     * The value of the {@code agent} query parameter.
     */
    final String parameter;

    /**
     * The field of each task entry that holds the task in this agent's format.
     */
    final String taskField;

    /**
     * How OdoBot executes the tasks, or null if it does not.
     */
    final ExecutionMode mode;

    Agent(String parameter, String taskField, ExecutionMode mode){
        this.parameter = parameter;
        this.taskField = taskField;
        this.mode = mode;
    }

    public ExecutionMode getMode(){
        return mode;
    }

    public static boolean isValidAgent(String value){
        return Arrays.stream(Agent.values()).map(agent->agent.taskField.toLowerCase())
                .anyMatch(agentString->value.toLowerCase().contains(agentString));
    }

    public static Agent fromField(String field){
        return Arrays.stream(Agent.values())
                .filter(agent->agent.parameter.equals(field))
                .findFirst()
                .orElseThrow(()->new RuntimeException("Unknown agent value"));
    }


}
