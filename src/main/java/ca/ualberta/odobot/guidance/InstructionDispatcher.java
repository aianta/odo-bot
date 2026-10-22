package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.guidance.instructions.MultiStepInstruction;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The harness's side of getting instructions to OdoX, and the only place OdoBot holds them. It decides when each
 * instruction goes out, according to the task's {@link DispatchPolicy}, and keeps the task's idle timeout. The guidance
 * channel ({@link GuidanceConnectionManager}) only carries what it is given.
 *
 * <p>An instruction first waits out its pause. Then it joins one queue, which sends in order, and only while:</p>
 * <ul>
 *     <li>OdoX has a page ready, see {@link OdoClient#onPageReady}. Each message is addressed to that page by its
 *     {@code pageId}, and OdoX delivers it only to that page; otherwise it answers NOT_DELIVERED, see
 *     {@link #onNotDelivered}.</li>
 *     <li>for an EXECUTE, the DOM has settled, if the policy has a settle gate, see {@link #onPageActivity}. Messages
 *     behind a held EXECUTE wait with it, so nothing overtakes it.</li>
 * </ul>
 * <p>Every EXECUTE gets one reply: EXECUTE_DONE once the page has run it, or EXECUTION_RESULT for a multi-step
 * instruction. If the policy says so ({@link DispatchPolicy#awaitDone}), the next EXECUTE waits for it, and an EXECUTE
 * without one is lost. Either way the harness is told, see {@link RequestManager#onExecutionDone}.</p>
 * <p>{@link #cancel} drops everything still waiting once the task is over, and nothing goes out after it.</p>
 *
 * <p>Calls must come from the context OdoX's socket was bound on, or hop to it, as the public methods here do.</p>
 */
public class InstructionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InstructionDispatcher.class);

    /**
     * The source EXECUTE messages carry. It names the guidance channel's manager, as it always has.
     */
    private static final String SOURCE = "GuidanceConnectionManager";

    /**
     * A message whose pause has elapsed, waiting in the queue for its turn.
     * @param followUp true for a follow-up of a multi-step instruction, which doesn't count as a step.
     */
    private record Outgoing(JsonObject message, boolean followUp){
        boolean isExecute(){
            return "EXECUTE".equals(message.getString("type"));
        }
    }

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
     * True once the task is over, see {@link #cancel}. Nothing is sent until the next task starts.
     */
    private boolean cancelled = false;

    /**
     * Messages whose pause has elapsed, in the order they go out.
     */
    private final Deque<Outgoing> queue = new ArrayDeque<>();

    /**
     * EXECUTE messages sent to a page, by id, so that one OdoX could not deliver can be dealt with, see
     * {@link #onNotDelivered}. Kept until the task ends.
     */
    private final Map<String, Outgoing> sent = new HashMap<>();

    /**
     * The id of the EXECUTE sent last and not yet reported done, or null. With {@link DispatchPolicy#awaitDone}, the next
     * EXECUTE waits for it.
     */
    private String inFlight = null;

    /**
     * The timers that limit how long OdoX may take to report each sent EXECUTE done, by id.
     */
    private final Map<String, Long> doneTimers = new HashMap<>();

    /**
     * EXECUTE messages that expect no EXECUTION_RESULT, by id. Their futures complete once they have actually gone out.
     */
    private final Map<String, Promise<JsonObject>> awaitingSend = new ConcurrentHashMap<>();

    public long timeoutTimer = -1l;

    /**
     * The timer of the current wait for the DOM to settle, or -1 if there is none.
     */
    private long settleTimer = -1l;

    /**
     * When ({@link System#nanoTime()}) the current wait for the DOM to settle began, or -1 if there is none.
     */
    private long settleSince = -1l;

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
        this.cancelled = false;
        this.sent.clear();
        stopDoneTimers();
        this.inFlight = null;
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
     * @return true if the call came from elsewhere and was moved to the dispatcher's context, so the caller must stop.
     */
    private boolean hopToContext(Runnable call){
        Context context = context();
        if(context != null && Vertx.currentContext() != context){
            context.runOnContext(v->call.run());
            return true;
        }
        return false;
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
     * other instructions, a future that completes with the EXECUTE message once it has gone out.
     */
    public Future<JsonObject> submit(Instruction instruction, boolean followUp, Consumer<Instruction> next){

        if(cancelled){
            log.info("The task has ended, dropping instruction {}.", instruction);
            return Future.failedFuture("The task has ended");
        }

        if(!followUp){
            resetTimeout();
        }

        JsonObject executionRequest = new JsonObject()
                .put("type", "EXECUTE")
                .put("id", UUID.randomUUID().toString())
                .put("source", SOURCE)
                .put("executionId", client.getRequestManager().getExecutionId().toString())
                .mergeIn(instruction.toJson());

        //Every EXECUTE gets one reply: EXECUTE_DONE, or EXECUTION_RESULT for a multi-step instruction.
        String id = executionRequest.getString("id");
        Future<JsonObject> reply = guidance().expectReplyTo(executionRequest);
        reply.onComplete(done->onReply(id, done));

        Future<JsonObject> result;

        //Multi-step instructions produce follow-up instructions from their execution result.
        if(instruction instanceof MultiStepInstruction multiStepInstruction){
            log.info("Configuring handling the response for {} instruction.", executionRequest.getString("action"));

            result = reply.compose(response->"EXECUTION_RESULT".equals(response.getString("type"))?
                    Future.succeededFuture(response) :
                    Future.failedFuture("OdoX answered %s (%s) instead of an EXECUTION_RESULT".formatted(response.getString("type"), response.getString("status"))));
            result.onFailure(err->log.error(err.getMessage(), err))
                    .onSuccess(response->multiStepInstruction.onExecutionResult(executionRequest, response, next));
        }else{
            Promise<JsonObject> sent = Promise.promise();
            awaitingSend.put(executionRequest.getString("id"), sent);
            result = sent.future();
        }

        DispatchPolicy policy = policy();
        afterPause(followUp? policy.followUpPause() : policy.instructionPause(), ()->enqueue(new Outgoing(executionRequest, followUp)));

        return result;
    }

    /**
     * Send one of the guidance channel's own messages to the page, e.g. SHOW_NAVIGATION_OPTIONS. It takes its turn in
     * the queue, so it never overtakes an instruction waiting there.
     *
     * <p>Replies to the page don't come here: the page is waiting for them, so they go straight to the page that asked,
     * see {@link GuidanceConnectionManager}.</p>
     */
    public void send(JsonObject message){
        if(hopToContext(()->send(message))){
            return;
        }
        enqueue(new Outgoing(message, false));
    }

    private void enqueue(Outgoing outgoing){
        if(cancelled){
            log.info("The task has ended, dropping {} {}.", outgoing.message().getString("type"), outgoing.message().getString("id"));
            return;
        }
        outgoing.message().put("delayed", false);
        queue.addLast(outgoing);
        pump();
    }

    /**
     * Send what is at the front of the queue, for as long as it can go out.
     */
    private void pump(){
        while(!cancelled && !queue.isEmpty()){
            if(!client.isPageReady() || !guidance().canSend()){
                log.info("No page is ready, {} message(s) wait for one.", queue.size());
                return;
            }

            Outgoing next = queue.peekFirst();
            if(next.isExecute() && inFlight != null && policy().awaitDone()){
                log.info("Holding the next instruction until OdoX reports {} done.", inFlight);
                return;
            }
            if(next.isExecute() && settleTimer != -1l){
                log.info("Delay requested for instruction, holding it until the DOM has settled.");
                next.message().put("delayed", true);
                return;
            }

            queue.pollFirst();
            write(next);
        }
    }

    /**
     * Send a message to the page that is ready now.
     */
    private void write(Outgoing outgoing){
        JsonObject message = outgoing.message().put("pageId", client.pageId());
        if(outgoing.isExecute()){
            String id = message.getString("id");
            sent.put(id, outgoing);
            inFlight = id;
            startDoneTimer(id, message);
            guidance().sendExecute(message);
        }else{
            guidance().sendQueued(message);
        }
    }

    /**
     * A page is ready: send what waited for one.
     */
    public void onPageReady(){
        if(hopToContext(this::onPageReady)){
            return;
        }
        pump();
    }

    /**
     * OdoX could not deliver a message, because the page it was addressed to had gone by the time it arrived.
     * What happens to an EXECUTE depends on {@link DispatchPolicy#onLost}.
     *
     * @param notDelivered OdoX's NOT_DELIVERED reply, whose {@code replyTo} is the message's id.
     * @return true if the message was an EXECUTE this dispatcher sent, false for anything else.
     */
    public boolean onNotDelivered(JsonObject notDelivered){
        String id = notDelivered.getString("replyTo");
        Outgoing lost = sent.remove(id);
        if(lost == null || cancelled){
            return lost != null;
        }
        stopDoneTimer(id);
        if(id.equals(inFlight)){
            inFlight = null;
        }

        JsonObject message = lost.message();
        DispatchPolicy.LostInstruction onLost = policy().onLost();
        log.warn("OdoX could not deliver {} {} to page {} ({}): {}.", message.getString("action"), message.getString("id"),
                message.getString("pageId"), notDelivered.getString("reason"), onLost);

        switch (onLost){
            case REQUEUE_FOR_NEXT_PAGE -> {
                queue.addFirst(lost);
                pump();
            }
            case UNCOUNT_AND_REOBSERVE -> {
                guidance().failReply(id, "OdoX could not deliver it: " + notDelivered.getString("reason"));
                client.getRequestManager().onInstructionLost(message, lost.followUp());
                pump();
            }
        }
        return true;
    }

    /**
     * OdoX answered a sent EXECUTE: EXECUTE_DONE, or EXECUTION_RESULT for a multi-step instruction. The next
     * instruction may go out, and the harness is told.
     */
    private void onReply(String id, AsyncResult<JsonObject> reply){
        Outgoing outgoing = sent.remove(id);
        if(outgoing == null){
            return; //Already dealt with: not delivered, lost for want of a reply, or the task is over.
        }
        stopDoneTimer(id);
        if(id.equals(inFlight)){
            inFlight = null;
        }
        if(reply.succeeded()){
            client.getRequestManager().onExecutionDone(outgoing.message(), reply.result());
        }
        pump();
    }

    /**
     * Limit how long OdoX may take to report an EXECUTE done: {@link DispatchPolicy#doneTimeout}, plus the duration of a
     * {@code wait} action, which OdoX reports done only once it has waited.
     */
    private void startDoneTimer(String id, JsonObject message){
        stopDoneTimer(id);
        long timeout = policy().doneTimeout() + UnchartedStepObserver.waitMillis(message.getJsonObject("unchartedAction"));
        doneTimers.put(id, client.vertx().setTimer(timeout, timer->onDoneTimeout(id, timeout)));
    }

    private void stopDoneTimer(String id){
        Long timer = doneTimers.remove(id);
        if(timer != null){
            client.vertx().cancelTimer(timer);
        }
    }

    private void stopDoneTimers(){
        doneTimers.values().forEach(client.vertx()::cancelTimer);
        doneTimers.clear();
    }

    /**
     * OdoX hasn't reported an EXECUTE done in time. A policy that waits for EXECUTE_DONE treats the instruction as lost;
     * one that doesn't, the charted one, only logs it: charted agents advance on the effects they observe, never on
     * EXECUTE_DONE.
     */
    private void onDoneTimeout(String id, long timeout){
        doneTimers.remove(id);
        Outgoing outgoing = sent.get(id);
        if(outgoing == null || cancelled){
            return;
        }

        if(!policy().awaitDone()){
            log.warn("OdoX has not reported {} {} done after {} ms; the agent goes on regardless.",
                    outgoing.message().getString("action"), id, timeout);
            return;
        }

        log.warn("OdoX did not report {} {} done within {} ms, treating it as lost.", outgoing.message().getString("action"), id, timeout);
        sent.remove(id);
        if(id.equals(inFlight)){
            inFlight = null;
        }
        guidance().failReply(id, "OdoX did not report it done within %d ms".formatted(timeout));
        client.getRequestManager().onInstructionLost(outgoing.message(), outgoing.followUp());
        pump();
    }

    /**
     * An EXECUTE message has actually gone to OdoX. Completes its future if it expects no EXECUTION_RESULT, and tells the
     * harness.
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
        if(hopToContext(()->afterPause(pauseMs, action))){
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
     * The task is over: drop every message still waiting, cancel the timers, and fail the requests still waiting for
     * OdoX on either channel. Nothing goes out after this, even if a page becomes ready, until the next task starts.
     */
    public void cancel(){
        if(hopToContext(this::cancel)){
            return;
        }

        cancelled = true;

        pauseTimers.forEach(client.vertx()::cancelTimer);
        pauseTimers.clear();

        if(!queue.isEmpty()){
            log.info("The task has ended, dropping {} message(s) that were waiting to be sent.", queue.size());
        }
        queue.clear();
        sent.clear();
        stopDoneTimers();
        inFlight = null;

        guidance().failPending("The task ended");
        client.getEventConnectionManager().failPending("The task ended");
        awaitingSend.values().forEach(sent->sent.tryFail("The task ended before the instruction was sent"));
        awaitingSend.clear();

        if(settleTimer != -1l){
            client.vertx().cancelTimer(settleTimer);
            settleTimer = -1l;
            settleSince = -1l;
        }

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

        if(settleTimer != -1l){
            client.vertx().cancelTimer(settleTimer);
        }

        long now = System.nanoTime();
        if(settleSince == -1l){
            settleSince = now;
        }
        long waitedMs = (now - settleSince) / 1_000_000;
        long delay = settleDelay(waitedMs, policy.settleDelay(), policy.settleMaxDelay());

        if(delay < policy.settleDelay()){
            log.info("DOM is still changing after {} ms, the next execution instruction will be sent in at most {} ms.", waitedMs, delay);
        }else{
            log.info("Next execution instruction will be delayed to give time for DOM to settle.");
        }
        settleTimer = client.vertx().setTimer(delay, id->{
            settleTimer = -1;
            settleSince = -1;
            pump();
        });
    }

    /**
     * (Re)start the task timeout. The task fails if the timeout elapses before the next reset.
     */
    public void resetTimeout(){
        //Keep the timeout on the event loop that runs the agent, so the two never run concurrently.
        if(hopToContext(this::resetTimeout)){
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
