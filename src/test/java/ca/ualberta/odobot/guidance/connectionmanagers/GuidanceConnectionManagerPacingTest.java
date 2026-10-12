package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import io.vertx.core.Context;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instructions wait out their pause on a timer, so the event loop keeps handling OdoX's messages meanwhile.
 */
public class GuidanceConnectionManagerPacingTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();

    /**
     * A connected guidance socket that records what is sent over it, and when.
     */
    public static class RecordingConnection extends WebSocketConnection {
        public final List<Long> sentAt = new CopyOnWriteArrayList<>();
        public final List<JsonObject> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(String data) {
            sentAt.add(System.currentTimeMillis());
            sent.add(new JsonObject(data));
        }
    }

    /**
     * An instruction with nothing to it, since only when it is sent matters here.
     */
    public static class TestInstruction extends Instruction {
        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return "TestInstruction";
        }
    }

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private GuidanceConnectionManager manager;
    private final RecordingConnection connection = new RecordingConnection();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = new OdoClient(UUID.randomUUID(), vertx);
        client.setRequestManager(new RequestManager(client){
            @Override
            public UUID getExecutionId() {
                return EXECUTION_ID;
            }

            @Override
            public long getTimeout() {
                return 60000;
            }

            @Override
            public void onExecutionInstructionSent(JsonObject instruction) {
            }
        });
        manager = client.getGuidanceConnectionManager();
        manager.instructionPause = 600;
        manager.followUpPause = 200;
        //The manager runs on the context its socket was bound on.
        run(()->client.setGuidance(connection));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void thePausesKeepTheirLengths() {
        GuidanceConnectionManager fresh = new OdoClient(UUID.randomUUID(), vertx).getGuidanceConnectionManager();
        assertEquals(3000, fresh.instructionPause);
        assertEquals(1000, fresh.followUpPause);
    }

    @Test
    void anInstructionIsSentOnceItsPauseHasElapsed() throws Exception {
        long start = System.currentTimeMillis();
        run(()->manager.sendExecutionInstruction(new TestInstruction(), false, next->{}));

        waitUntil(()->connection.sent.size() == 1);
        assertTrue(connection.sentAt.get(0) - start >= 600, "sent before the pause elapsed");
        assertEquals("EXECUTE", connection.sent.get(0).getString("type"));
        assertEquals(EXECUTION_ID.toString(), connection.sent.get(0).getString("executionId"));
    }

    @Test
    void aFollowUpWaitsTheShorterPause() throws Exception {
        long start = System.currentTimeMillis();
        run(()->manager.sendExecutionInstruction(new TestInstruction(), true, next->{}));

        waitUntil(()->connection.sent.size() == 1);
        long waited = connection.sentAt.get(0) - start;
        assertTrue(waited >= 200, "sent before the pause elapsed");
        assertTrue(waited < 600, "a follow-up waited the full pause");
    }

    @Test
    void theEventLoopIsFreeDuringThePause() throws Exception {
        long start = System.currentTimeMillis();
        run(()->manager.sendExecutionInstruction(new TestInstruction(), false, next->{}));

        //Anything else queued on the context runs right away, not after the instruction is sent.
        long[] ranAt = new long[1];
        run(()->ranAt[0] = System.currentTimeMillis());
        assertTrue(ranAt[0] - start < 300, "the context was held for " + (ranAt[0] - start) + " ms");
        assertTrue(connection.sent.isEmpty(), "the instruction went out before its pause");
    }

    @Test
    void anInstructionCalledFromAnotherThreadIsPacedOnTheAgentsContext() throws Exception {
        //E.g. an agent that decided on a worker thread.
        manager.sendExecutionInstruction(new TestInstruction(), false, next->{});

        waitUntil(()->connection.sent.size() == 1);
    }

    @Test
    void noInstructionIsSentOnceTheTimersAreCancelled() throws Exception {
        run(()->manager.sendExecutionInstruction(new TestInstruction(), false, next->{}));
        run(()->manager.cancelTimers());

        Thread.sleep(900);
        assertTrue(connection.sent.isEmpty(), "an instruction was sent after the task ended");
    }

    @Test
    void cancellingStopsTheTaskTimeout() throws Exception {
        run(()->manager.resetTimeout());
        assertNotEquals(-1l, manager.timeoutTimer);

        run(()->manager.cancelTimers());
        assertEquals(-1l, manager.timeoutTimer);
    }

    /**
     * Run the action on the manager's context and wait for it to finish.
     */
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
