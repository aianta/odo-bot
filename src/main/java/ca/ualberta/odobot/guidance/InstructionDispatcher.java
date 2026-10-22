package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.guidance.instructions.MultiStepInstruction;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The harness's side of getting instructions to OdoX. It decides when each instruction goes out, according to the
 * task's {@link DispatchPolicy}, and keeps the task's idle timeout. The guidance channel
 * ({@link GuidanceConnectionManager}) only carries what it is given.
 *
 * <p>Instructions wait out a pause, and, while the DOM keeps changing, a hold for it to settle. Everything still
 * waiting is dropped by {@link #cancel}, once the task is over.</p>
 */
public class InstructionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InstructionDispatcher.class);

    /**
     * The source EXECUTE messages carry. It names the guidance channel's manager, as it always has.
     */
    private static final String SOURCE = "GuidanceConnectionManager";

    private final OdoClient client;

    /**
     * The policy of each execution mode, see {@link DispatchPolicy#forMode}.
     */
    private Function<ExecutionMode, DispatchPolicy> policies = DispatchPolicy::forMode;

    /**
     * The mode of the current task, which picks its policy.
     */
    private ExecutionMode mode = ExecutionMode.CHARTED;

    /**
     * EXECUTE messages that expect no reply, by id. Their futures complete once they have actually gone out.
     */
    private final Map<String, Promise<JsonObject>> awaitingSend = new ConcurrentHashMap<>();

    public long timeoutTimer = -1l;

    private long executionInstructionDelayTimer = -1l;

    /**
     * When ({@link System#nanoTime()}) the current wait for the DOM to settle began, or -1 if there is none.
     */
    private long executionInstructionDelaySince = -1l;

    /**
     * The instruction held for the DOM to settle, or null.
     */
    private JsonObject executionInstruction = null;

    /**
     * The timers of messages waiting out their pause, see {@link #afterPause}.
     */
    private final Set<Long> pauseTimers = new HashSet<>();

    public InstructionDispatcher(OdoClient client){
        this.client = client;
    }

    /**
     * Pace the instructions of a task executed in the given mode.
     */
    public void start(ExecutionMode mode){
        this.mode = mode;
    }

    /**
     * @param policies the policy of each execution mode, e.g. shorter pauses in tests.
     */
    public void setPolicies(Function<ExecutionMode, DispatchPolicy> policies){
        this.policies = policies;
    }

    /**
     * @return the policy of the current task.
     */
    public DispatchPolicy policy(){
        return policies.apply(mode);
    }

    private GuidanceConnectionManager guidance(){
        return client.getGuidanceConnectionManager();
    }

    /**
     * @return the context OdoX's socket was bound on, which runs the agent. The dispatcher's timers run there too.
     */
    private Context context(){
        return guidance().context();
    }

    /**
     * Send an instruction to OdoX for execution.
     *
     * @param instruction the instruction to execute.
     * @param followUp true if the instruction was produced by the follow-up logic of a {@link MultiStepInstruction}.
     *                 Follow-ups do not reset the task timeout, and are sent after a shorter pause.
     * @param next if the instruction is a {@link MultiStepInstruction}, receives the follow-up instructions it produces
     *             from its EXECUTION_RESULT.
     * @return for a {@link MultiStepInstruction}, a future that completes with the instruction's EXECUTION_RESULT. For
     * other instructions, which get no reply, a future that completes with the EXECUTE message once it has gone out.
     */
    public Future<JsonObject> submit(Instruction instruction, boolean followUp, Consumer<Instruction> next){

        if(!followUp){
            resetTimeout();
        }

        JsonObject executionRequest = new JsonObject()
                .put("type", "EXECUTE")
                .put("id", UUID.randomUUID().toString())
                .put("source", SOURCE)
                .put("executionId", client.getRequestManager().getExecutionId().toString())
                .mergeIn(instruction.toJson());

        Future<JsonObject> result;

        //Multi-step instructions produce follow-up instructions from their execution result.
        if(instruction instanceof MultiStepInstruction multiStepInstruction){
            log.info("Configuring handling the response for {} instruction.", executionRequest.getString("action"));

            result = guidance().expectExecutionResult(executionRequest);
            result.onFailure(err->log.error(err.getMessage(), err))
                    .onSuccess(response->multiStepInstruction.onExecutionResult(executionRequest, response, next));
        }else{
            Promise<JsonObject> sent = Promise.promise();
            awaitingSend.put(executionRequest.getString("id"), sent);
            result = sent.future();
        }

        DispatchPolicy policy = policy();
        afterPause(followUp? policy.followUpPause() : policy.instructionPause(), ()->send(executionRequest));

        return result;
    }

    /**
     * Send an instruction whose pause has elapsed, or hold it while the DOM is settling.
     */
    private void send(JsonObject data){
        if(executionInstructionDelayTimer == -1l){
            log.info("No delay for instruction, sending immediately.");
            data.put("delayed", false);
            guidance().sendExecute(data);
        }else{
            log.info("Delay requested for instruction, saving instruction to be sent after delay.");
            data.put("delayed", true);
            if(executionInstruction != null){
                log.error("Fatal Error, previous instruction was still waiting to be sent!");
                client.vertx().setTimer(policy().settleDelay(), timer->{
                    send(data);
                });
                return;

            }
            executionInstruction = data;
        }
    }

    /**
     * An EXECUTE message has actually gone to OdoX, including one that waited for a page to be ready. Completes its
     * future if it expects no reply, and tells the harness.
     */
    public void onExecuteSent(JsonObject message){
        Promise<JsonObject> sent = awaitingSend.remove(message.getString("id"));
        if(sent != null){
            sent.complete(message);
        }
        client.getRequestManager().onExecutionInstructionSent(message);
    }

    /**
     * Run the action once the pause has elapsed, on the context that runs the agent. Unlike the sleep it replaces, the
     * pause does not hold up the event loop, so OdoX's messages keep being handled meanwhile.
     */
    public void afterPause(long pauseMs, Runnable action){
        Context context = context();
        if(context != null && Vertx.currentContext() != context){
            context.runOnContext(v->afterPause(pauseMs, action));
            return;
        }

        long[] timer = new long[1];
        timer[0] = client.vertx().setTimer(pauseMs, id->{
            pauseTimers.remove(timer[0]);
            action.run();
        });
        pauseTimers.add(timer[0]);
    }

    /**
     * Cancel the task timeout and every message still waiting to be sent, and fail the requests still waiting for
     * OdoX, once the task is over.
     */
    public void cancel(){
        Context context = context();
        if(context != null && Vertx.currentContext() != context){
            context.runOnContext(v->cancel());
            return;
        }

        pauseTimers.forEach(client.vertx()::cancelTimer);
        pauseTimers.clear();

        guidance().failPending("The task ended");
        awaitingSend.values().forEach(sent->sent.tryFail("The task ended before the instruction was sent"));
        awaitingSend.clear();

        if(executionInstructionDelayTimer != -1l){
            client.vertx().cancelTimer(executionInstructionDelayTimer);
            executionInstructionDelayTimer = -1l;
            executionInstructionDelaySince = -1l;
        }
        executionInstruction = null;

        if(timeoutTimer != -1l){
            client.vertx().cancelTimer(timeoutTimer);
            timeoutTimer = -1l;
        }
    }

    /**
     * @param waitedMs how long the current wait for the DOM to settle has lasted.
     * @return how much longer to wait: the settle delay, cut short so the whole wait never exceeds the max delay.
     */
    static long settleDelay(long waitedMs, long delayMs, long maxDelayMs){
        return Math.max(1, Math.min(delayMs, maxDelayMs - waitedMs));
    }

    /**
     * The page changed: its DOM, its network traffic or its location. If the policy has a settle gate, the next
     * instruction is held until the DOM has not changed for {@link DispatchPolicy#settleDelay}, but no longer than
     * {@link DispatchPolicy#settleMaxDelay} after the wait began.
     */
    public void onPageActivity(){
        DispatchPolicy policy = policy();
        if(!policy.settleGate()){
            return;
        }

        if(executionInstructionDelayTimer != -1l){
            client.vertx().cancelTimer(executionInstructionDelayTimer);
        }

        long now = System.nanoTime();
        if(executionInstructionDelaySince == -1l){
            executionInstructionDelaySince = now;
        }
        long waitedMs = (now - executionInstructionDelaySince) / 1_000_000;
        long delay = settleDelay(waitedMs, policy.settleDelay(), policy.settleMaxDelay());

        if(delay < policy.settleDelay()){
            log.info("DOM is still changing after {} ms, the next execution instruction will be sent in at most {} ms.", waitedMs, delay);
        }else{
            log.info("Next execution instruction will be delayed to give time for DOM to settle.");
        }
        executionInstructionDelayTimer = client.vertx().setTimer(delay, id->{
            executionInstructionDelayTimer = -1;
            executionInstructionDelaySince = -1;
            if(executionInstruction != null){
                log.info("Delayed instruction is being sent now!");
                guidance().sendExecute(executionInstruction);
                executionInstruction = null;
            }

        });
    }

    /**
     * (Re)start the task timeout. The task fails if the timeout elapses before the next reset.
     */
    public void resetTimeout(){
        //Keep the timeout on the event loop that runs the agent, so the two never run concurrently.
        Context context = context();
        if(context != null && Vertx.currentContext() != context){
            context.runOnContext(v->resetTimeout());
            return;
        }

        if(timeoutTimer != -1l){ //Only if it hasn't yet been set.
            client.vertx().cancelTimer(timeoutTimer);
        }

        timeoutTimer = client.vertx().setTimer(client.getRequestManager().getTimeout(), id->{
            log.info("Task execution timed out!");
            client.getRequestManager().getEvaluationComplete().tryFail("Timeout!");
        });
    }
}
