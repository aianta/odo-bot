package ca.ualberta.odobot.guidance.instructions;

import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.snippet2xml.Snippet2XMLService;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;

/**
 * What the follow-up logic of a {@link MultiStepInstruction} needs from the agent that emitted it.
 */
public interface FollowUpContext {

    TaskPlannerService taskPlanner();

    Snippet2XMLService snippet2XML();

    SqliteService sqlite();

    Neo4JUtils neo4J();

    /**
     * @return the task description and resolved parameters of the task being executed.
     */
    ExecutionRequest request();

    /**
     * Replace the last instruction of every nav path whose last instruction is of the given type. This is how the
     * follow-up tells the agent which side effect to expect.
     */
    void replaceLastInstruction(Class<? extends Instruction> ifLastIs, Instruction with);

    void recoverFromFailedNode(String nodeId);

    String artifactPath(String suffix);

}
