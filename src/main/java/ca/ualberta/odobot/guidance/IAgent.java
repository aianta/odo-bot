package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import java.util.function.Consumer;

/**
 * An agent consumes observations and emits instructions. The harness ({@link RequestManager}) delivers
 * observations and executes instructions.
 *
 * <ul>
 *     <li><b>First observation.</b> The first entity an agent receives is an
 *     {@link ca.ualberta.odobot.semanticflow.model.Observation}, which OdoX sends as its first event after
 *     START_TRANSMISSION. The agent recognises it as the first from its own state, does its set-up there
 *     (e.g. task interpretation), then plans and emits its first instruction. Any entity that arrives before
 *     the first Observation is ignored.</li>
 *     <li><b>Emit and observe.</b> Emitting an instruction is fire-and-forget. The agent never sees
 *     EXECUTION_RESULTs; it judges progress only from what it observes afterwards.</li>
 *     <li><b>Re-activation.</b> A later Observation means "re-plan from the current page", and re-activates
 *     an agent after {@link #stop()}.</li>
 *     <li><b>Threading.</b> Every call runs on the GuidanceVerticle event loop, so agents must not block.</li>
 *     <li><b>Isolation.</b> An agent affects the outside world only by emitting instructions. It holds no
 *     reference to the OdoClient, RequestManager or the connection managers.</li>
 * </ul>
 */
public interface IAgent {

    /**
     * @param consumer provided by the RequestManager and bound to this agent. Every instruction the agent
     *                 produces goes here.
     */
    void setInstructionConsumer(Consumer<Instruction> consumer);

    /**
     * Receives every input: the Observation, timeline events and OdoX feedback.
     */
    void observationHandler(TimelineEntity timelineEntity);

    /**
     * Stop emitting instructions and release resources. This does not dispose the agent; a later
     * Observation re-activates it.
     */
    void stop();

}
