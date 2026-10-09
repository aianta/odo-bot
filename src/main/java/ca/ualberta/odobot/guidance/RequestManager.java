package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.common.LlmClientConfig;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
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
     * How the current task is executed. It decides which timeline feeds the active agent.
     */
    private ExecutionMode mode = ExecutionMode.CHARTED;

    /**
     * True once the task has completed, failed or timed out. Instructions are dropped after that.
     */
    private boolean ended = false;

    private boolean firstInstructionSent = false;

    /**
     * Token usage of the current task. LLM calls made through {@link #llmScope} are counted toward it while the task runs.
     */
    private TaskTokenUsage taskTokenUsage = null;

    /**
     * The task's LLM settings and token usage record, given to the agents and services built for the task. It holds
     * them only while the task runs, so tasks running at the same time do not share them.
     */
    private final LlmCallScope llmScope = new LlmCallScope();

    private boolean tokenUsageFinished = false;

    /**
     * How the current task ended, saved with its token usage.
     */
    private String taskOutcome = null;

    /**
     * What went wrong in the harness after the current task ended, saved with its token usage, or null.
     */
    private String harnessError = null;

    /**
     * Wall clock time of the current task, saved with its token usage. The harness may hand in one that already marks
     * the setup before the task starts, see {@link #setTaskTiming}.
     */
    private TaskTiming taskTiming = null;

    /**
     * The OpenAI client settings of the task, or null to leave each service's own configuration in place. Calls made
     * through {@link #llmScope} use them while the task runs.
     */
    private LlmClientConfig llmConfig = null;

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
            if(taskTiming != null){
                taskTiming.markExecutionEnd();
            }
            finishTokenUsage(done.succeeded()? "completed" : "failed: " + done.cause().getMessage());
            restoreLlmConfig();
            client.getEventConnectionManager().stopObservingUnchartedSteps();
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
     * @param llmConfig the OpenAI client settings for the next task, or null to use each service's own configuration.
     */
    public RequestManager setLlmConfig(LlmClientConfig llmConfig) {
        this.llmConfig = llmConfig;
        return this;
    }

    /**
     * @return the task's LLM settings and token usage record, for the agents and services built for the task. It is
     * filled when the task starts and emptied when it ends.
     */
    public LlmCallScope getLlmScope() {
        return llmScope;
    }

    private void restoreLlmConfig(){
        llmScope.clearConfig();
    }

    /**
     * @return the token usage of the current (or last) task, or null if no task has been started.
     */
    public TaskTokenUsage getTokenUsage() {
        return taskTokenUsage;
    }

    /**
     * @return the wall clock time of the current (or last) task, or null if no task has been started.
     */
    public TaskTiming getTaskTiming() {
        return taskTiming;
    }

    /**
     * @param taskTiming the timing of the next task, whose setup the harness has already marked. Without one, the next
     *                   task's timing starts with its execution.
     */
    /**
     * @param harnessError what went wrong in the harness after the task ended (saving its artifacts or scoring it),
     *                     saved with the task's usage as {@code harnessError}.
     */
    public RequestManager setHarnessError(String harnessError) {
        this.harnessError = harnessError;
        return this;
    }

    public RequestManager setTaskTiming(TaskTiming taskTiming) {
        this.taskTiming = taskTiming;
        return this;
    }

    /**
     * Stop counting LLM calls toward the task, and save its token usage to {@code <evalId>-tokens.json} in the
     * experiment folder.
     */
    private void finishTokenUsage(String outcome){
        if(taskTokenUsage == null || tokenUsageFinished){
            return; //No task was started, or its usage was already finished.
        }
        tokenUsageFinished = true;
        taskOutcome = outcome;
        llmScope.stopCounting();

        //Inference is only counted during execution, so a call still in flight counts up to the end of execution.
        Long executionEnd = taskTiming == null? null : taskTiming.executionEndNanos();
        taskTokenUsage.freezeInference(executionEnd == null? System.nanoTime() : executionEnd);

        log.info("Task {} token usage: {} input, {} output, {} total tokens over {} LLM calls ({})",
                evalId, taskTokenUsage.inputTokens, taskTokenUsage.outputTokens, taskTokenUsage.totalTokens, taskTokenUsage.llmCalls, outcome);
        if(taskTiming != null && taskTiming.executionMs() != null){
            log.info("Task {} execution: {} ms waiting on inference, {} ms other work, of {} ms ({} failed LLM attempts, {} failed calls)",
                    evalId, taskTiming.inferenceMs(taskTokenUsage.inferenceMs()), taskTiming.otherExecutionMs(taskTokenUsage.inferenceMs()),
                    taskTiming.executionMs(), taskTokenUsage.failedAttempts, taskTokenUsage.failedCalls);
        }

        saveTaskUsage();
    }

    /**
     * Save the token usage and timing of the task to {@code <evalId>-tokens.json} in the experiment folder. It is first
     * saved when the task ends; the harness saves it again once it has finished timing the task (artifacts and scoring).
     */
    public void saveTaskUsage(){
        if(taskTokenUsage == null || evalId == null || experimentFolderPath == null){
            return; //No task was started, or not an evaluation run, so there is nowhere to save the usage.
        }

        JsonObject usage = new JsonObject()
                .put("evalId", evalId)
                .put("experimentId", experimentId)
                .put("executionId", executionId == null? null : executionId.toString())
                .put("mode", mode.name())
                .put("outcome", taskOutcome)
                .mergeIn(taskTokenUsage.toJson());
        if(harnessError != null){
            usage.put("harnessError", harnessError);
        }
        if(taskTiming != null){
            usage.put("timing", taskTiming.toJson(taskTokenUsage.inferenceMs()));
        }

        String fileName = "%s/%s-tokens.json".formatted(experimentFolderPath, evalId).replaceAll("\\|","-");
        try(FileWriter fw = new FileWriter(fileName);
            BufferedWriter bw = new BufferedWriter(fw)
        ){
            bw.write(usage.encodePrettily());
            bw.flush();
        }catch (IOException e){
            log.error("Failed to save token usage for {}: {}", evalId, e.getMessage());
        }
    }

    /**
     * Start executing a task with the given agent. OdoX answers START_TRANSMISSION with an Observation, which
     * reaches the agent through its timeline as its first observation.
     *
     * @param mode how the task is executed. The main timeline is built in every mode; the uncharted observation timeline,
     *             which feeds the agent in {@link ExecutionMode#UNCHARTED} mode, only in that mode.
     */
    public void startTask(IAgent agent, ExecutionMode mode, UUID executionId, long timeout){
        this.executionId = executionId;
        this.timeout = timeout;
        this.activeAgent = agent;
        this.mode = mode;
        this.ended = false;
        this.firstInstructionSent = false;

        //Every LLM call made while the task runs is counted toward it, whichever agent makes it.
        this.taskTokenUsage = new TaskTokenUsage(()->activeAgent == null? "none" : activeAgent.getClass().getSimpleName());
        this.tokenUsageFinished = false;
        this.taskOutcome = null;
        this.harnessError = null;

        //Every chat completion made through the task's scope uses the task's client settings, if it has any.
        llmScope.start(this.taskTokenUsage, llmConfig);

        //Use the timing the harness handed in, unless it belongs to an earlier task.
        if(taskTiming == null || taskTiming.executionStarted()){
            taskTiming = new TaskTiming();
        }
        taskTiming.markExecutionStart();

        //Write the task's raw events to disk as they arrive, rather than holding them in memory until the task ends.
        if(evalId != null && experimentFolderPath != null){
            client.getEventConnectionManager().getEventProcessor().streamRawEventsTo(
                    Path.of("%s/%s.events.jsonl.part".formatted(experimentFolderPath, evalId).replaceAll("\\|","-")));
        }

        client.getEventConnectionManager().getEventProcessor().setUnchartedTimelineEnabled(mode == ExecutionMode.UNCHARTED);

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
     * Receives every timeline entity and every piece of OdoX feedback, and routes it to the active agent, except in
     * {@link ExecutionMode#UNCHARTED} mode, where the agent is fed from the uncharted timeline instead, see {@link #onUnchartedObservation}.
     */
    public void onObservation(TimelineEntity entity){

        //Give the DOM time to settle before sending the next instruction.
        if(entity instanceof Effect || entity instanceof NetworkEvent || entity instanceof ApplicationLocationChange){
            client.getGuidanceConnectionManager().resetExecutionInstructionDelay();
        }

        if(activeAgent != null && mode != ExecutionMode.UNCHARTED){
            activeAgent.observationHandler(entity);
        }
    }

    /**
     * Receives the observations of the uncharted timeline, which is only built in {@link ExecutionMode#UNCHARTED} mode: the one OdoX
     * sends when transmission starts, and one per uncharted step.
     */
    public void onUnchartedObservation(Observation observation){
        if(activeAgent != null && mode == ExecutionMode.UNCHARTED){
            activeAgent.observationHandler(observation);
        }
    }

    /**
     * An EXECUTE message has just been sent to OdoX. Uncharted steps are observed from here, see {@link UnchartedStepObserver}.
     */
    public void onExecutionInstructionSent(JsonObject instruction){
        if("uncharted_step".equals(instruction.getString("action"))){
            client.getEventConnectionManager().observeUnchartedStep(instruction);
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

        if(instruction instanceof TaskComplete taskComplete){
            if(taskComplete.answer != null){
                //Record the answer before taskComplete() saves the raw events.
                log.info("Agent answered: {}", taskComplete.answer);
                client.getEventConnectionManager().getEventProcessor().injectTaskAnswer(taskComplete.answer);
            }
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
