package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManagerPacingTest;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.task.TaskSettings;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness executes at most {@code maxSteps} of the agent's instructions, then ends the task as failed.
 */
class RequestManagerStepLimitTest {

    /**
     * An agent that emits whatever the test tells it to.
     */
    public static class ScriptedAgent implements IAgent {
        Consumer<Instruction> emit;

        @Override
        public void setInstructionConsumer(Consumer<Instruction> consumer) {
            this.emit = consumer;
        }

        @Override
        public void observationHandler(TimelineEntity timelineEntity) {
        }

        @Override
        public void stop() {
        }
    }

    private Vertx vertx;
    private Context context;
    private RequestManager requestManager;
    private final GuidanceConnectionManagerPacingTest.RecordingConnection guidance = new GuidanceConnectionManagerPacingTest.RecordingConnection();
    private final ScriptedAgent agent = new ScriptedAgent();
    private final Promise<Void> evaluation = Promise.promise();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        OdoClient client = new OdoClient(UUID.randomUUID(), vertx);
        client.getGuidanceConnectionManager().instructionPause = 1;
        client.getGuidanceConnectionManager().followUpPause = 1;
        requestManager = client.getRequestManager();
        run(()->{
            client.getGuidanceConnectionManager().updateConnection(guidance);
            requestManager.setEvaluationComplete(evaluation);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void start(int maxSteps) throws Exception {
        run(()->{
            requestManager.setMaxSteps(maxSteps);
            requestManager.startTask(agent, ExecutionMode.UNCHARTED, UUID.randomUUID(), 60000);
        });
    }

    private long executeMessages(){
        return guidance.sent.stream().filter(message->"EXECUTE".equals(message.getString("type"))).count();
    }

    @Test
    void stepsUpToTheLimitAreExecuted() throws Exception {
        start(3);
        for(int i = 0; i < 3; i++){
            run(()->agent.emit.accept(new GuidanceConnectionManagerPacingTest.TestInstruction()));
        }

        waitUntil(()->executeMessages() == 3);
        assertFalse(evaluation.future().isComplete());
        assertEquals(3, requestManager.getSteps());
    }

    @Test
    void theStepPastTheLimitEndsTheTask() throws Exception {
        start(2);
        for(int i = 1; i <= 2; i++){
            run(()->agent.emit.accept(new GuidanceConnectionManagerPacingTest.TestInstruction()));
            long sent = i;
            waitUntil(()->executeMessages() == sent);
        }
        run(()->agent.emit.accept(new GuidanceConnectionManagerPacingTest.TestInstruction()));

        assertTrue(evaluation.future().failed());
        assertEquals("Step limit of 2 reached", evaluation.future().cause().getMessage());
        assertEquals("failed: Step limit of 2 reached", requestManager.getTaskOutcome());
        Thread.sleep(200);
        assertEquals(2, executeMessages(), "the instruction past the limit was executed");
    }

    @Test
    void withoutALimitEveryStepIsExecuted() throws Exception {
        run(()->{
            requestManager.setMaxSteps(null);
            requestManager.startTask(agent, ExecutionMode.CHARTED, UUID.randomUUID(), 60000);
        });
        for(int i = 0; i < 40; i++){
            run(()->agent.emit.accept(new GuidanceConnectionManagerPacingTest.TestInstruction()));
        }

        assertFalse(evaluation.future().isComplete());
        assertEquals(40, requestManager.getSteps());
    }

    @Test
    void theStepCountStartsOverWithEachTask() throws Exception {
        start(2);
        run(()->agent.emit.accept(new GuidanceConnectionManagerPacingTest.TestInstruction()));
        assertEquals(1, requestManager.getSteps());

        run(()->requestManager.startTask(agent, ExecutionMode.UNCHARTED, UUID.randomUUID(), 60000));
        assertEquals(0, requestManager.getSteps());
    }

    @Test
    void aLimitBelowOneIsRejected(){
        assertThrows(IllegalArgumentException.class, ()->requestManager.setMaxSteps(0));
    }

    @Test
    void theDefaultLimitIsThirty(){
        assertEquals(30, RequestManager.DEFAULT_MAX_STEPS);
        assertEquals(30, TaskSettings.maxSteps(new JsonObject(), ExecutionMode.UNCHARTED));
        assertEquals(30, TaskSettings.maxSteps(new JsonObject(), ExecutionMode.CHARTED));
        assertEquals(5, TaskSettings.maxSteps(new JsonObject().put("maxSteps", 5), ExecutionMode.UNCHARTED));
        assertThrows(IllegalArgumentException.class, ()->TaskSettings.maxSteps(new JsonObject().put("maxSteps", 0), ExecutionMode.UNCHARTED));
        assertThrows(IllegalArgumentException.class, ()->TaskSettings.maxSteps(new JsonObject().put("maxSteps", "30"), ExecutionMode.UNCHARTED));
    }

    @Test
    void onlyChartedTasksCanIgnoreTheLimit(){
        JsonObject ignoring = new JsonObject().put("ignoreMaxSteps", true);

        assertNull(TaskSettings.maxSteps(ignoring, ExecutionMode.CHARTED));
        assertEquals(30, TaskSettings.maxSteps(new JsonObject().put("ignoreMaxSteps", false), ExecutionMode.CHARTED));
        assertThrows(IllegalArgumentException.class, ()->TaskSettings.maxSteps(ignoring, ExecutionMode.UNCHARTED));
        assertThrows(IllegalArgumentException.class, ()->TaskSettings.maxSteps(ignoring, ExecutionMode.HYBRID));
        assertThrows(IllegalArgumentException.class, ()->TaskSettings.maxSteps(new JsonObject().put("ignoreMaxSteps", "yes"), ExecutionMode.CHARTED));
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
