package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.common.LlmClientConfig;
import ca.ualberta.odobot.common.TaskArtifacts;
import ca.ualberta.odobot.guidance.instructions.*;
import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedStep;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
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
     * How many steps a task may take unless it sets {@code maxSteps}.
     */
    public static final int DEFAULT_MAX_STEPS = 30;

    /**
     * The most instructions the active agent may have executed in the current task, or null for no limit. Follow-ups
     * of multi-step instructions don't count. An instruction past the limit ends the task as failed.
     */
    private Integer maxSteps = DEFAULT_MAX_STEPS;

    /**
     * How many of the agent's instructions have been executed in the current task.
     */
    private int steps = 0;

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

    /**
     * Where the task runs, saved with its token usage, or null.
     */
    private JsonObject environment = null;

    /**
     * The answer the agent reported when it completed the task or judged it infeasible, or null if it reported none.
     */
    private String agentAnswer = null;

    /**
     * True if the agent ended the current task by judging it infeasible.
     */
    private boolean judgedInfeasible = false;

    /**
     * Fields the harness adds to the task's token usage file after the task ends, e.g. its final URL and score.
     */
    private final JsonObject usageFields = new JsonObject();

    /**
     * Decides when the agent's instructions go out to OdoX, and keeps the task's idle timeout.
     */
    private final InstructionDispatcher dispatcher;

    /**
     * The current task's uncharted steps, from the agent's thoughts to the observation of their result.
     */
    private final StepLedger stepLedger = new StepLedger();

    public RequestManager(OdoClient client){
        this.client = client;
        this.client.setRequestManager(this);
        this.dispatcher = new InstructionDispatcher(client);
    }

    public InstructionDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * @return the uncharted steps of the current (or last) task.
     */
    public StepLedger getStepLedger() {
        return stepLedger;
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

    /**
     * @return where the task's artifacts go, or null if this is not an evaluation run (no evalId or experiment folder).
     */
    public TaskArtifacts getArtifacts() {
        return evalId == null || experimentFolderPath == null? null : TaskArtifacts.of(experimentFolderPath, evalId);
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
        this.evaluationComplete.future().onComplete(done->{
            //Nothing more goes to OdoX for this task: stop the timeout and drop any instruction still waiting to be sent.
            dispatcher.cancel();
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

    /**
     * @return how the current (or last) task ended, or null if it hasn't.
     */
    public String getTaskOutcome() {
        return taskOutcome;
    }

    public String getHarnessError() {
        return harnessError;
    }

    /**
     * @return the answer the agent reported with {@link TaskComplete} or an infeasible {@link GiveUp}, or null.
     */
    public String getAgentAnswer() {
        return agentAnswer;
    }

    /**
     * @return true if the agent ended the current (or last) task by judging it infeasible.
     */
    public boolean isJudgedInfeasible() {
        return judgedInfeasible;
    }

    /**
     * Add a field to the task's token usage file, which {@link #saveTaskUsage()} writes next.
     */
    public RequestManager putUsageField(String key, Object value) {
        usageFields.put(key, value);
        return this;
    }

    /**
     * @param maxSteps the most instructions the agent may have executed in the next task, at least 1, or null for no
     *                 limit.
     */
    public RequestManager setMaxSteps(Integer maxSteps) {
        if(maxSteps != null && maxSteps < 1){
            throw new IllegalArgumentException("maxSteps must be at least 1, got " + maxSteps);
        }
        this.maxSteps = maxSteps;
        return this;
    }

    /**
     * @return how many of the agent's instructions have been executed in the current (or last) task.
     */
    public int getSteps() {
        return steps;
    }

    /**
     * @param environment where the task runs, saved with its token usage as {@code environment}.
     */
    public RequestManager setEnvironment(JsonObject environment) {
        this.environment = environment;
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
        TaskArtifacts artifacts = getArtifacts();
        if(taskTokenUsage == null || artifacts == null){
            return; //No task was started, or not an evaluation run, so there is nowhere to save the usage.
        }

        JsonObject usage = new JsonObject()
                .put("evalId", evalId)
                .put("experimentId", experimentId)
                .put("executionId", executionId == null? null : executionId.toString())
                .put("mode", mode.name())
                .put("outcome", taskOutcome)
                .put("steps", steps)
                .put("maxSteps", maxSteps)
                .mergeIn(taskTokenUsage.toJson());
        if(harnessError != null){
            usage.put("harnessError", harnessError);
        }
        if(environment != null){
            usage.put("environment", environment);
        }
        if(taskTiming != null){
            usage.put("timing", taskTiming.toJson(taskTokenUsage.inferenceMs()));
        }
        usage.mergeIn(usageFields);

        try(FileWriter fw = new FileWriter(artifacts.file(TaskArtifacts.TOKENS).toFile());
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
        this.steps = 0;
        this.stepLedger.clear();

        //Every LLM call made while the task runs is counted toward it, whichever agent makes it.
        this.taskTokenUsage = new TaskTokenUsage(()->activeAgent == null? "none" : activeAgent.getClass().getSimpleName());
        this.tokenUsageFinished = false;
        this.taskOutcome = null;
        this.harnessError = null;
        this.agentAnswer = null;
        this.judgedInfeasible = false;
        this.usageFields.clear();

        //Every chat completion made through the task's scope uses the task's client settings, if it has any.
        llmScope.start(this.taskTokenUsage, llmConfig);

        //Use the timing the harness handed in, unless it belongs to an earlier task.
        if(taskTiming == null || taskTiming.executionStarted()){
            taskTiming = new TaskTiming();
        }
        taskTiming.markExecutionStart();

        //Write the task's raw events to disk as they arrive, rather than holding them in memory until the task ends.
        TaskArtifacts artifacts = getArtifacts();
        if(artifacts != null){
            client.getEventConnectionManager().getEventProcessor().streamRawEventsTo(artifacts.file(TaskArtifacts.EVENTS_PART));
        }

        client.getEventConnectionManager().getEventProcessor().setUnchartedTimelineEnabled(mode == ExecutionMode.UNCHARTED);

        agent.setInstructionConsumer(instruction->onInstruction(agent, instruction));

        dispatcher.start(mode);

        //Arm the timeout now, so the task also fails if OdoX never sends the first observation or the agent never produces an instruction.
        dispatcher.resetTimeout();

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
            dispatcher.onPageActivity();
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
        if(observation.getStepNumber() != null){
            stepLedger.observed(observation.getStepNumber(), observation, System.currentTimeMillis(),
                    client.getEventConnectionManager().getEventProcessor().lastEventIndex());
        }
        if(activeAgent != null && mode == ExecutionMode.UNCHARTED){
            activeAgent.observationHandler(observation);
        }
    }

    /**
     * An EXECUTE message has just been sent to the page it is addressed to.
     */
    public void onExecutionInstructionSent(JsonObject instruction){
        log.info("Task {}: sent {} {} to page {}.", evalId, instruction.getString("action"), instruction.getString("id"), instruction.getString("pageId"));
        if(isUnchartedStep(instruction)){
            stepLedger.sent(instruction.getInteger("step"), instruction.getString("id"), System.currentTimeMillis(),
                    client.getEventConnectionManager().getEventProcessor().lastEventIndex());
        }
    }

    private static boolean isUnchartedStep(JsonObject instruction){
        return "uncharted_step".equals(instruction.getString("action")) && instruction.getInteger("step") != null;
    }

    /**
     * OdoX reported an instruction done (EXECUTE_DONE), or answered it with its EXECUTION_RESULT.
     *
     * <p>An uncharted step is observed from here, now that it has run, see {@link UnchartedStepObserver}. Charted agents
     * are not told: they advance on the effects they observe on the timeline (e.g. the network request a click is known
     * to cause), so EXECUTE_DONE is only a sanity check for them, and a failure is logged.</p>
     *
     * @param reply EXECUTE_DONE, whose status is "ok", "failed" or "page-gone", or an EXECUTION_RESULT.
     */
    public void onExecutionDone(JsonObject instruction, JsonObject reply){
        String status = reply.getString("status", "ok");
        if(!"ok".equals(status)){
            log.warn("Task {}: OdoX reported {} {} {}{}.", evalId, instruction.getString("action"), instruction.getString("id"), status,
                    reply.getString("reason") == null? "" : ": " + reply.getString("reason"));
        }
        if(isUnchartedStep(instruction)){
            stepLedger.done(instruction.getInteger("step"), System.currentTimeMillis(), status, reply.getString("reason"));
        }
        if(ended){
            return;
        }
        if("uncharted_step".equals(instruction.getString("action"))){
            client.getEventConnectionManager().observeUnchartedStep(instruction);
        }
    }

    /**
     * OdoX could not deliver an instruction, or never reported it done, and the policy drops it, see
     * {@link DispatchPolicy.LostInstruction#UNCOUNT_AND_REOBSERVE}. It isn't counted as a step, and an uncharted step is
     * observed again from now, so the agent decides anew from the page on the screen.
     */
    public void onInstructionLost(JsonObject instruction, boolean followUp){
        if(isUnchartedStep(instruction)){
            stepLedger.lost(instruction.getInteger("step"));
        }
        if(ended){
            return;
        }
        if(!followUp && steps > 0){
            steps--;
        }
        log.info("Task {}: instruction {} was not delivered and is not counted; {} step(s) taken.", evalId, instruction.getString("id"), steps);
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
            recordStep(instruction, "dropped: the task had ended or its agent was not active");
            return;
        }

        if(!followUp && (instruction instanceof TaskComplete || instruction instanceof GiveUp)){
            recordStep(instruction, null);
        }

        if(instruction instanceof TaskComplete taskComplete){
            if(taskComplete.answer != null){
                //Record the answer before taskComplete() saves the raw events.
                log.info("Agent answered: {}", taskComplete.answer);
                agentAnswer = taskComplete.answer;
                client.getEventConnectionManager().getEventProcessor().injectTaskAnswer(taskComplete.answer);
            }
            taskComplete();
            return;
        }

        if(instruction instanceof GiveUp giveUp){
            log.info("Agent gave up: {}", giveUp);
            judgedInfeasible = giveUp.infeasible;
            if(giveUp.infeasible && giveUp.answer != null){
                agentAnswer = giveUp.answer;
                client.getEventConnectionManager().getEventProcessor().injectTaskAnswer(giveUp.answer);
            }
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

        if(!followUp){
            if(maxSteps != null && steps >= maxSteps){
                log.info("Task {} reached its step limit of {}, ending it instead of executing: {}", evalId, maxSteps, instruction);
                recordStep(instruction, "step limit of %d reached".formatted(maxSteps));
                if(evaluationComplete != null){
                    evaluationComplete.tryFail("Step limit of %d reached".formatted(maxSteps));
                }
                return;
            }
            steps++;
            recordStep(instruction, null);
        }

        Future<JsonObject> result = dispatcher.submit(instruction, followUp, next->execute(source, next, true));

        if(!followUp && !firstInstructionSent){
            firstInstructionSent = true;
            result.onSuccess(done->log.info("First instruction sent for execution!!"));
        }
    }

    /**
     * Record an uncharted step, or the end of the task, the agent emitted, in the {@link #stepLedger}. Instructions that
     * are neither an uncharted step nor carry the agent's thoughts are not steps, and are not recorded.
     *
     * @param notExecuted why the harness does not execute it, or null.
     */
    private void recordStep(Instruction instruction, String notExecuted){
        Integer stepNumber = instruction instanceof UnchartedStep step? Integer.valueOf(step.step)
                : instruction.getAgentTrace() != null? Integer.valueOf(instruction.getAgentTrace().stepNumber()) : null;
        if(stepNumber == null){
            return;
        }
        String emitted = instruction instanceof UnchartedStep? "uncharted_step" : instruction.getClass().getSimpleName();
        stepLedger.emitted(stepNumber, emitted, instruction.getAgentTrace(), notExecuted);
    }

    private void taskComplete(){
        //Only the event channel is told: OdoX's background ends the transmission. The page has nothing to do.
        client.getEventConnectionManager().notifyPathComplete();

        if(evalId != null && evaluationComplete != null){
            //If the evaluation ID is not null (meaning this was an evaluation run, output the raw events from this execution to the appropriate folder.
            evaluationComplete.tryComplete();
        }
        //The raw events are not cleared here: the harness saves them once the evaluation completes, then clears them.
    }

}
