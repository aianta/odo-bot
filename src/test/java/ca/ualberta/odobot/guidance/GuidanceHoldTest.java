package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManagerPacingTest.TestInstruction;
import io.vertx.core.Context;
import io.vertx.core.Future;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static ca.ualberta.odobot.guidance.ChannelRoutingTest.message;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guidance messages wait while OdoX has no page ready, e.g. while the page navigates, and go out in order once a page
 * is ready.
 */
class GuidanceHoldTest {

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private GuidanceConnectionManager manager;
    private final ChannelRoutingTest.RecordingSocket socket = new ChannelRoutingTest.RecordingSocket();

    /**
     * The EXECUTE messages the harness was told about, see {@link RequestManager#onExecutionInstructionSent}.
     */
    private final List<JsonObject> reportedSent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
        client.setRequestManager(new RequestManager(client){
            @Override
            public UUID getExecutionId() {
                return ChannelRoutingTest.EXECUTION_ID;
            }

            @Override
            public long getTimeout() {
                return 60000;
            }

            @Override
            public void onExecutionInstructionSent(JsonObject instruction) {
                reportedSent.add(instruction);
            }
        });
        manager = client.getGuidanceConnectionManager();
        manager.instructionPause = 1;
        manager.followUpPause = 1;
        run(()->client.setSocket(socket));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private List<JsonObject> executes(){
        return socket.sent("EXECUTE");
    }

    private Future<JsonObject> execute() throws Exception {
        AtomicReference<Future<JsonObject>> result = new AtomicReference<>();
        run(()->result.set(manager.sendExecutionInstruction(new TestInstruction(), false, next->{})));
        //Let the instruction's pause elapse, so instructions reach the queue in the order they were sent.
        Thread.sleep(100);
        return result.get();
    }

    @Test
    void instructionsWaitForAPageAndGoOutInOrder() throws Exception {
        Future<JsonObject> first = execute();
        Future<JsonObject> second = execute();

        assertTrue(executes().isEmpty(), "an instruction went out with no page ready");
        assertTrue(reportedSent.isEmpty(), "the harness was told an instruction went out before it did");
        assertFalse(first.isComplete());

        run(()->client.route(message("guidance", "PAGE_READY")));

        waitUntil(()->executes().size() == 2);
        assertEquals(first.result().getString("id"), executes().get(0).getString("id"));
        assertEquals(second.result().getString("id"), executes().get(1).getString("id"));
        assertEquals(2, reportedSent.size(), "the harness is told when the held instructions go out");
    }

    @Test
    void anInstructionIsHeldAgainOnceThePageIsGone() throws Exception {
        run(()->client.route(message("guidance", "PAGE_READY")));
        execute();
        waitUntil(()->executes().size() == 1);

        run(()->client.route(message("guidance", "PAGE_GONE")));
        Future<JsonObject> held = execute();
        assertEquals(1, executes().size());
        assertFalse(held.isComplete());

        run(()->client.route(message("guidance", "PAGE_READY")));
        waitUntil(()->executes().size() == 2);
        assertTrue(held.succeeded(), "an instruction without a reply completes once it has gone out");
    }

    @Test
    void aNewSocketForgetsThePage() throws Exception {
        run(()->client.route(message("guidance", "PAGE_READY")));
        assertTrue(client.isPageReady());

        ChannelRoutingTest.RecordingSocket reconnected = new ChannelRoutingTest.RecordingSocket();
        run(()->client.setSocket(reconnected));
        assertFalse(client.isPageReady());

        execute();
        assertTrue(reconnected.sent("EXECUTE").isEmpty(), "an instruction went out before OdoX reported its page again");
    }

    @Test
    void eventMessagesDoNotWaitForAPage() throws Exception {
        run(()->client.getEventConnectionManager().startTransmitting());

        assertEquals(1, socket.sent("START_TRANSMISSION").size());
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
