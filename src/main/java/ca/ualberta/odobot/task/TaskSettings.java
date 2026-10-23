package ca.ualberta.odobot.task;

import ca.ualberta.odobot.common.LlmClientConfig;
import ca.ualberta.odobot.dataentry2label.impl.DataEntry2LabelServiceImpl;
import ca.ualberta.odobot.explorer.Agent;
import ca.ualberta.odobot.guidance.ExecutionMode;
import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig;
import ca.ualberta.odobot.snippet2xml.impl.Snippet2XMLServiceImpl;
import ca.ualberta.odobot.taskplanner.impl.TaskPlannerServiceImpl;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task settings read from the evaluate request, shared by every task of an experiment.
 */
public final class TaskSettings {

    private TaskSettings(){}

    /**
     * The settings of the Qwen3.8 agent, from the {@code qwen} object of the evaluate request. Missing keys take the
     * defaults of {@link QwenAgentConfig#fromJson}.
     *
     * @throws IllegalArgumentException if the settings are invalid.
     */
    public static QwenAgentConfig qwenConfig(JsonObject evaluateRequest){
        return QwenAgentConfig.fromJson(evaluateRequest.getJsonObject("qwen", new JsonObject()), evaluateRequest.getJsonObject("llm", new JsonObject()));
    }

    /**
     * The OpenAI client settings of both agents, from the {@code llm} object of the evaluate request. Missing keys take
     * the defaults of {@link LlmClientConfig#fromJson}.
     *
     * @return null if the request has no {@code llm} object, in which case the charted services keep their own
     * configuration.
     * @throws IllegalArgumentException if the settings are invalid.
     */
    public static LlmClientConfig llmConfig(JsonObject evaluateRequest){
        JsonObject llm = evaluateRequest.getJsonObject("llm");
        return llm == null? null : LlmClientConfig.fromJson(llm);
    }

    /**
     * @return the most instructions the task's agent may have executed: the task's {@code maxSteps}, or
     * {@link RequestManager#DEFAULT_MAX_STEPS}. Null for no limit, when a charted task sets {@code ignoreMaxSteps}:
     * charted agents follow paths computed from the navigation model, so they rarely need one.
     * @throws IllegalArgumentException if {@code maxSteps} isn't a whole number of at least 1, {@code ignoreMaxSteps}
     * isn't a boolean, or {@code ignoreMaxSteps} is set for an agent that isn't charted.
     */
    public static Integer maxSteps(JsonObject task, ExecutionMode mode){
        Object ignore = task.getValue("ignoreMaxSteps", false);
        if(!(ignore instanceof Boolean ignoreMaxSteps)){
            throw new IllegalArgumentException("'ignoreMaxSteps' must be true or false.");
        }
        if(ignoreMaxSteps && mode != ExecutionMode.CHARTED){
            throw new IllegalArgumentException("'ignoreMaxSteps' is only for charted agents.");
        }

        Object value = task.getValue("maxSteps", RequestManager.DEFAULT_MAX_STEPS);
        if(!(value instanceof Integer steps) || steps < 1){
            throw new IllegalArgumentException("'maxSteps' must be a whole number of at least 1.");
        }
        return ignoreMaxSteps? null : steps;
    }

    /**
     * @return whether a step of the task's uncharted agent fails when the model's reasoning does not come back in full:
     * the task's {@code requireFullReasoning}, false by default.
     * @throws IllegalArgumentException if {@code requireFullReasoning} isn't a boolean, is true for an agent that isn't
     * uncharted, or is true while the request's {@code llm} settings can't guarantee full reasoning, see
     * {@link LlmClientConfig#fullReasoningProblem()}.
     */
    public static boolean requireFullReasoning(JsonObject task, ExecutionMode mode, JsonObject evaluateRequest){
        Object value = task.getValue("requireFullReasoning", false);
        if(!(value instanceof Boolean require)){
            throw new IllegalArgumentException("'requireFullReasoning' must be true or false.");
        }
        if(!require){
            return false;
        }
        if(mode != ExecutionMode.UNCHARTED){
            throw new IllegalArgumentException("'requireFullReasoning' is only for the uncharted agent.");
        }
        String problem = qwenConfig(evaluateRequest).client().fullReasoningProblem();
        if(problem != null){
            throw new IllegalArgumentException("'requireFullReasoning' can't be met: " + problem + ".");
        }
        return true;
    }

    /**
     * @return whether the experiment saves each task's trajectory to {@code db/trajectories.db}: the request's
     * {@code saveTrajectories}, false by default.
     * @throws IllegalArgumentException if {@code saveTrajectories} isn't a boolean.
     */
    public static boolean saveTrajectories(JsonObject evaluateRequest){
        Object value = evaluateRequest.getValue("saveTrajectories", false);
        if(!(value instanceof Boolean save)){
            throw new IllegalArgumentException("'saveTrajectories' must be true or false.");
        }
        return save;
    }

    /**
     * @return how the charted agent selects paths: the request's {@code pathSelectionMode}, HYBRID by default.
     */
    public static ExecutionRequest.PathSelectionMode pathSelectionMode(JsonObject evaluateRequest){
        return evaluateRequest.containsKey("pathSelectionMode")
                ? ExecutionRequest.PathSelectionMode.valueOf(evaluateRequest.getString("pathSelectionMode"))
                : ExecutionRequest.PathSelectionMode.HYBRID;
    }

    /**
     * @return the models that answer the LLM calls of a task, for telemetry.
     */
    public static String modelDescription(Agent agent, JsonObject evaluateRequest){
        if(agent.getMode() == ExecutionMode.UNCHARTED){
            return qwenConfig(evaluateRequest).client().model();
        }
        LlmClientConfig llm = llmConfig(evaluateRequest);
        if(llm != null){
            return llm.model();
        }
        //The charted services' own models.
        Set<String> modelInfo = new LinkedHashSet<>();
        modelInfo.add(Snippet2XMLServiceImpl.model);
        modelInfo.add(DataEntry2LabelServiceImpl.model);
        modelInfo.add(TaskPlannerServiceImpl.model);
        return modelInfo.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
