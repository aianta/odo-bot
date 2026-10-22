package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.InstructionDispatcherPacingTest.TestInstruction;
import ca.ualberta.odobot.guidance.instructions.TaskComplete;
import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedStep;
import ca.ualberta.odobot.guidance.instructions.uncharted.Wait;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static ca.ualberta.odobot.guidance.ChannelRoutingTest.message;
import static ca.ualberta.odobot.guidance.ChannelRoutingTest.pageReady;
import static ca.ualberta.odobot.guidance.ChannelRoutingTest.reply;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatcher addresses each instruction to the page that is ready, holds instructions in one queue while no page is,
 * and deals with an instruction OdoX could not deliver according to the task's policy.
 */
class InstructionDispatcherTest {

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private RequestManager requestManager;
    private InstructionDispatcher dispatcher;
    private final ChannelRoutingTest.RecordingSocket socket = new ChannelRoutingTest.RecordingSocket();
    private final RequestManagerStepLimitTest.ScriptedAgent agent = new RequestManagerStepLimitTest.ScriptedAgent();
    private final Promise<Void> evaluation = Promise.promise();

    /**
     * The instructions the harness was told were lost, see {@link RequestManager#onInstructionLost}.
     */
    private final List<JsonObject> lost = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
        requestManager = new RequestManager(client){
            @Override
            public void onInstructionLost(JsonObject instruction, boolean followUp) {
                lost.add(instruction);
                super.onInstructionLost(instruction, followUp);
            }
        };
        dispatcher = requestManager.getDispatcher();
        InstructionDispatcherPacingTest.usePauses(client, 1, 1);
        run(()->{
            client.setSocket(socket);
            requestManager.setEvaluationComplete(evaluation);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void start(ExecutionMode mode) throws Exception {
        run(()->requestManager.startTask(agent, mode, UUID.randomUUID(), 60000));
    }

    private List<JsonObject> executes(){
        return socket.sent("EXECUTE");
    }

    private static UnchartedStep step(int number){
        return new UnchartedStep(number, "wait a moment", new Wait(1.0));
    }

    private static JsonObject notDelivered(JsonObject message){
        return reply(message, "NOT_DELIVERED").put("reason", "page-gone");
    }

    private static JsonObject pageGone(String pageId){
        return message("guidance", "PAGE_GONE").put("pageId", pageId);
    }

    @Test
    void anInstructionIsAddressedToThePageThatIsReady() throws Exception {
        start(ExecutionMode.CHARTED);
        run(()->client.route(pageReady("page-1")));
        run(()->agent.emit.accept(new TestInstruction()));
        waitUntil(()->executes().size() == 1);

        run(()->client.route(pageGone("page-1")));
        run(()->client.route(pageReady("page-2")));
        run(()->agent.emit.accept(new TestInstruction()));
        waitUntil(()->executes().size() == 2);

        assertEquals("page-1", executes().get(0).getString("pageId"));
        assertEquals("page-2", executes().get(1).getString("pageId"));
    }

    @Test
    void anUnchartedStepOdoXCouldNotDeliverIsNotCountedAndIsObservedAgain() throws Exception {
        start(ExecutionMode.UNCHARTED);
        run(()->client.route(pageReady("page-1")));
        run(()->agent.emit.accept(step(1)));
        waitUntil(()->executes().size() == 1);
        assertEquals(1, requestManager.getSteps());

        JsonObject sent = executes().get(0);
        run(()->client.route(pageGone("page-1")));
        run(()->client.route(notDelivered(sent)));

        assertEquals(0, requestManager.getSteps(), "a step that never ran was counted");
        assertEquals(1, lost.size());
        assertEquals(sent.getString("id"), lost.get(0).getString("id"));

        //It is not sent again, to this page or the next: it was chosen from a screenshot of the page that is gone.
        run(()->client.route(pageReady("page-2")));
        Thread.sleep(200);
        assertEquals(1, executes().size());
    }

    @Test
    void aChartedInstructionOdoXCouldNotDeliverGoesToTheNextPage() throws Exception {
        start(ExecutionMode.CHARTED);
        run(()->client.route(pageReady("page-1")));
        run(()->agent.emit.accept(new TestInstruction()));
        waitUntil(()->executes().size() == 1);

        JsonObject sent = executes().get(0);
        run(()->client.route(pageGone("page-1")));
        run(()->client.route(notDelivered(sent)));
        assertEquals(1, executes().size(), "sent again before a page was ready");

        run(()->client.route(pageReady("page-2")));
        waitUntil(()->executes().size() == 2);

        JsonObject resent = executes().get(1);
        assertEquals(sent.getString("id"), resent.getString("id"));
        assertEquals("page-2", resent.getString("pageId"));
        assertEquals(1, requestManager.getSteps());
        assertTrue(lost.isEmpty());
    }

    @Test
    void nothingIsSentOnceTheTaskHasEnded() throws Exception {
        start(ExecutionMode.UNCHARTED);
        run(()->agent.emit.accept(step(1)));
        Thread.sleep(100);
        assertTrue(executes().isEmpty(), "sent with no page ready");

        run(()->evaluation.tryFail("Timeout!"));
        //E.g. the evaluator loads a page while it scores the task.
        run(()->client.route(pageReady("page-1")));
        run(()->dispatcher.submit(new TestInstruction(), false, next->{}));

        Thread.sleep(200);
        assertTrue(executes().isEmpty(), "a message went out after the task ended");
    }

    @Test
    void aMessageBehindAHeldInstructionWaitsItsTurn() throws Exception {
        start(ExecutionMode.CHARTED);
        dispatcher.setPolicies(mode->new DispatchPolicy(1, 1, true, 300, 1000, DispatchPolicy.LostInstruction.REQUEUE_FOR_NEXT_PAGE));
        run(()->client.route(pageReady("page-1")));
        //The page is busy, so the next instruction is held for the DOM to settle.
        run(()->dispatcher.onPageActivity());
        run(()->dispatcher.submit(new TestInstruction(), false, next->{}));
        Thread.sleep(100);
        run(()->dispatcher.send(new JsonObject().put("type", "CLEAR_NAVIGATION_OPTIONS")));

        assertTrue(socket.sent.stream().noneMatch(message->message.getString("channel").equals("guidance")),
                "a message overtook the held instruction");

        waitUntil(()->socket.sent("CLEAR_NAVIGATION_OPTIONS").size() == 1);
        List<String> order = socket.sent.stream()
                .filter(message->"guidance".equals(message.getString("channel")))
                .map(message->message.getString("type"))
                .toList();
        assertEquals(List.of("EXECUTE", "CLEAR_NAVIGATION_OPTIONS"), order);
        assertTrue(executes().get(0).getBoolean("delayed"));
    }

    @Test
    void completingTheTaskTellsOnlyTheEventChannel() throws Exception {
        start(ExecutionMode.UNCHARTED);
        run(()->client.route(pageReady("page-1")));
        run(()->agent.emit.accept(new TaskComplete("42")));

        List<JsonObject> pathComplete = socket.sent("PATH_COMPLETE");
        assertEquals(1, pathComplete.size());
        assertEquals("event", pathComplete.get(0).getString("channel"));
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
