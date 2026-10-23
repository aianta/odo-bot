package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedStep;
import ca.ualberta.odobot.guidance.instructions.uncharted.Wait;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.trajectory.AgentTrace;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness records each uncharted step, from the agent's thoughts to the observation of its result, for the task's
 * trajectory.
 */
class RequestManagerStepLedgerTest {

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private RequestManager requestManager;
    private final InstructionDispatcherPacingTest.RecordingConnection guidance = new InstructionDispatcherPacingTest.RecordingConnection();
    private final RequestManagerStepLimitTest.ScriptedAgent agent = new RequestManagerStepLimitTest.ScriptedAgent();
    private final Promise<Void> evaluation = Promise.promise();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
        InstructionDispatcherPacingTest.usePauses(client, 1, 1);
        requestManager = client.getRequestManager();
        run(()->{
            client.getGuidanceConnectionManager().updateConnection(guidance);
            client.onPageReady("page-1");
            requestManager.setEvaluationComplete(evaluation);
            requestManager.setMaxSteps(1);
            requestManager.startTask(agent, ExecutionMode.UNCHARTED, UUID.randomUUID(), 60000);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static AgentTrace trace(int step) {
        return new AgentTrace(step, null, "<think>why</think>Action: wait", "why", "Action: wait", "Wait", null, List.of(), List.of(),
                "NONE", null, new JsonArray(), "gen-" + step, null, null, null, null, "inline_think", List.of(), null,
                100, 100, 100, 100, 0);
    }

    private static UnchartedStep step(int number) {
        UnchartedStep step = new UnchartedStep(number, "Wait", new Wait(null));
        step.setAgentTrace(trace(number));
        return step;
    }

    @Test
    void aStepIsRecordedFromItsThoughtsToTheObservationOfItsResult() throws Exception {
        run(()->agent.emit.accept(step(1)));
        waitUntil(()->guidance.sent.stream().anyMatch(m->"EXECUTE".equals(m.getString("type"))));
        JsonObject execute = guidance.sent.stream().filter(m->"EXECUTE".equals(m.getString("type"))).findFirst().orElseThrow();
        run(()->client.route(ChannelRoutingTest.reply(execute, "EXECUTE_DONE").put("status", "ok")));
        Observation result = new Observation(Observation.Trigger.UNCHARTED_ACTION, null, null, null, 0, 1, execute.getString("id"));
        run(()->requestManager.onUnchartedObservation(result));

        StepLedger.Entry entry = requestManager.getStepLedger().entries().get(0);
        assertEquals(1, entry.stepNumber);
        assertEquals("uncharted_step", entry.emitted);
        assertEquals("why", entry.trace.reasoning());
        assertEquals(execute.getString("id"), entry.instructionId);
        assertNotNull(entry.sentAt);
        assertEquals(-1, entry.eventAtSend, "no event had arrived when it was sent");
        assertEquals("ok", entry.doneStatus);
        assertSame(result, entry.resultObservation);
        assertNull(entry.notExecuted);
        assertFalse(entry.lost);
    }

    @Test
    void aStepPastTheLimitIsRecordedAsNotExecuted() throws Exception {
        run(()->agent.emit.accept(step(1)));
        run(()->agent.emit.accept(step(2)));

        StepLedger.Entry second = requestManager.getStepLedger().entries().get(1);
        assertEquals(2, second.stepNumber);
        assertEquals("step limit of 1 reached", second.notExecuted);
        assertEquals("why", second.trace.reasoning(), "its thoughts are kept");
        assertNull(second.sentAt);
    }

    @Test
    void theLedgerStartsOverWithEachTask() throws Exception {
        run(()->agent.emit.accept(step(1)));
        assertEquals(1, requestManager.getStepLedger().entries().size());

        run(()->requestManager.startTask(agent, ExecutionMode.UNCHARTED, UUID.randomUUID(), 60000));
        assertTrue(requestManager.getStepLedger().entries().isEmpty());
    }

    private void run(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v->{
            try{
                action.run();
                done.complete(null);
            }catch (Throwable t){
                done.completeExceptionally(t);
            }
        });
        done.get(5, TimeUnit.SECONDS);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()){
            if(System.currentTimeMillis() > deadline){
                fail("timed out waiting");
            }
            Thread.sleep(20);
        }
    }
}
