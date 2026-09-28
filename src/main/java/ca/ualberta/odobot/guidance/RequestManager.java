package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * The harness side of task execution. It routes observations to the active agent, and executes the instructions
 * that agent emits.
 */
public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private Promise<Void> evaluationComplete;

    private OdoClient client = null;

    private String evalId = null;
    private String experimentId = null;
    private String experimentFolderPath = null;

    private UUID executionId = null;

    private long timeout;

    /**
     * The agent whose instructions are executed. Instructions from any other agent are dropped.
     */
    private IAgent activeAgent = null;

    /**
     * True once the task has completed, failed or timed out. Instructions are dropped after that.
     */
    private boolean ended = false;

    private boolean firstInstructionSent = false;

    public RequestManager(OdoClient client){
        this.client = client;
        this.client.setRequestManager(this);

    }

    public String getExperimentFolderPath() {
        return experimentFolderPath;
    }

    public RequestManager setExperimentFolderPath(String experimentFolderPath) {
        this.experimentFolderPath = experimentFolderPath;
        return this;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public RequestManager setExperimentId(String experimentId) {
        this.experimentId = experimentId;
        return this;
    }

    public String getEvalId() {
        return evalId;
    }

    public RequestManager setEvalId(String evalId) {
        this.evalId = evalId;
        return this;
    }

    public Promise<Void> getEvaluationComplete() {
        return evaluationComplete;
    }

    public RequestManager setEvaluationComplete(Promise<Void> evaluationComplete) {
        this.evaluationComplete = evaluationComplete;
        this.evaluationComplete.future().onSuccess(done->{
            //Clear timeout timer
            GuidanceVerticle._vertx.cancelTimer(client.getGuidanceConnectionManager().timeoutTimer);
        });
        this.evaluationComplete.future().onFailure(err->{
            log.error(err.getMessage(), err);
        });
        this.evaluationComplete.future().onComplete(done->{
            ended = true;
            if(activeAgent != null){
                activeAgent.stop();
            }
        });
        return this;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public long getTimeout() {
        return timeout;
    }

    /**
     * Start executing a task with the given agent. OdoX answers START_TRANSMISSION with an Observation, which
     * reaches the agent through the timeline as its first observation.
     */
    public void startTask(IAgent agent, UUID executionId, long timeout){
        this.executionId = executionId;
        this.timeout = timeout;
        this.activeAgent = agent;
        this.ended = false;
        this.firstInstructionSent = false;

        agent.setInstructionConsumer(instruction->onInstruction(agent, instruction));

        //Arm the timeout now, so the task also fails if OdoX never sends the first observation or the agent never produces an instruction.
        client.getGuidanceConnectionManager().resetTimeout();

        client.getEventConnectionManager().startTransmitting()//Turn on event transmissions
                .onSuccess(done->log.info("Waiting for the first observation from OdoX."))
                .onFailure(err->{
                    log.error("Error occurred while starting the task");
                    log.error(err.getMessage(), err);
                });
    }

    /**
     * Receives every timeline entity and every piece of OdoX feedback, and routes it to the active agent.
     */
    public void onObservation(TimelineEntity entity){

        //Give the DOM time to settle before sending the next instruction.
        if(entity instanceof Effect || entity instanceof NetworkEvent || entity instanceof ApplicationLocationChange){
            client.getGuidanceConnectionManager().resetExecutionInstructionDelay();
        }

        if(activeAgent != null){
            activeAgent.observationHandler(entity);
        }
    }

    public void onInstruction(IAgent source, Instruction instruction){
        execute(source, instruction, false);
    }

    /**
     * The only path from agents to OdoX.
     *
     * @param followUp true if the instruction was produced by the follow-up logic of a {@link MultiStepInstruction}.
     */
    private void execute(IAgent source, Instruction instruction, boolean followUp){

        if(ended || source != activeAgent){
            log.info("Dropping instruction, the task has ended or its agent is not active: {}", instruction);
            return;
        }

        if(instruction instanceof TaskComplete){
            taskComplete();
            return;
        }

        if(instruction instanceof GiveUp giveUp){
            log.info("Agent gave up: {}", giveUp.reason);
            if(evaluationComplete != null){
                evaluationComplete.tryFail(giveUp.reason);
            }
            return;
        }

        if(instruction instanceof NoOp){
            //Nothing to send to OdoX, record a no-op on the timeline.
            client.getEventConnectionManager().getEventProcessor().injectNoOp();
            return;
        }

        if(instruction instanceof WaitForLocationChange || instruction instanceof WaitForNetworkEvent){
            log.warn("Agents should not emit wait instructions, ignoring: {}", instruction);
            return;
        }

        Future<JsonObject> result = client.getGuidanceConnectionManager().sendExecutionInstruction(instruction, followUp, next->execute(source, next, true));

        if(!followUp && !firstInstructionSent){
            firstInstructionSent = true;
            result.onSuccess(done->log.info("First instruction sent for execution!!"));
        }
    }

    private void taskComplete(){
        client.getGuidanceConnectionManager().notifyPathComplete();
        client.getEventConnectionManager().notifyPathComplete();

        if(evalId != null && evaluationComplete != null){
            //If the evaluation ID is not null (meaning this was an evaluation run, output the raw events from this execution to the appropriate folder.
            evaluationComplete.tryComplete();
        }
        client.getEventConnectionManager().getEventProcessor().clearRawEvents();
    }

}
