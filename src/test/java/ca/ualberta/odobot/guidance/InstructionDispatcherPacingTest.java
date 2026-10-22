package ca.ualberta.odobot.guidance;

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
public class InstructionDispatcherPacingTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();

    /**
     * An open guidance channel that records what is sent over it, and when.
     */
    public static class RecordingConnection extends ChannelConnection {
        public final List<Long> sentAt = new CopyOnWriteArrayList<>();
        public final List<JsonObject> sent = new CopyOnWriteArrayList<>();

        public RecordingConnection(){
            super(Channel.GUIDANCE, null, ()->true);
        }

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

    /**
     * Have the client's dispatcher use the given pauses in every mode.
     */
    public static void usePauses(OdoClient client, long instructionPause, long followUpPause){
        client.getRequestManager().getDispatcher().setPolicies(mode->DispatchPolicy.forMode(mode).withPauses(instructionPause, followUpPause));
    }

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private InstructionDispatcher dispatcher;
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
        dispatcher = client.getRequestManager().getDispatcher();
        usePauses(client, 600, 200);
        //The dispatcher runs on the context the socket was bound on.
        run(()->{
            client.getGuidanceConnectionManager().updateConnection(connection);
            client.onPageReady("page-1");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void thePausesKeepTheirLengths() {
        for(ExecutionMode mode: ExecutionMode.values()){
            assertEquals(3000, DispatchPolicy.forMode(mode).instructionPause());
            assertEquals(1000, DispatchPolicy.forMode(mode).followUpPause());
        }
    }

    @Test
    void anInstructionIsSentOnceItsPauseHasElapsed() throws Exception {
        long start = System.currentTimeMillis();
        run(()->dispatcher.submit(new TestInstruction(), false, next->{}));

        waitUntil(()->connection.sent.size() == 1);
        assertTrue(connection.sentAt.get(0) - start >= 600, "sent before the pause elapsed");
        assertEquals("EXECUTE", connection.sent.get(0).getString("type"));
        assertEquals(EXECUTION_ID.toString(), connection.sent.get(0).getString("executionId"));
    }

    @Test
    void aFollowUpWaitsTheShorterPause() throws Exception {
        long start = System.currentTimeMillis();
        run(()->dispatcher.submit(new TestInstruction(), true, next->{}));

        waitUntil(()->connection.sent.size() == 1);
        long waited = connection.sentAt.get(0) - start;
        assertTrue(waited >= 200, "sent before the pause elapsed");
        assertTrue(waited < 600, "a follow-up waited the full pause");
    }

    @Test
    void theEventLoopIsFreeDuringThePause() throws Exception {
        long start = System.currentTimeMillis();
        run(()->dispatcher.submit(new TestInstruction(), false, next->{}));

        //Anything else queued on the context runs right away, not after the instruction is sent.
        long[] ranAt = new long[1];
        run(()->ranAt[0] = System.currentTimeMillis());
        assertTrue(ranAt[0] - start < 300, "the context was held for " + (ranAt[0] - start) + " ms");
        assertTrue(connection.sent.isEmpty(), "the instruction went out before its pause");
    }

    @Test
    void anInstructionCalledFromAnotherThreadIsPacedOnTheAgentsContext() throws Exception {
        //E.g. an agent that decided on a worker thread.
        dispatcher.submit(new TestInstruction(), false, next->{});

        waitUntil(()->connection.sent.size() == 1);
    }

    @Test
    void noInstructionIsSentOnceTheTimersAreCancelled() throws Exception {
        run(()->dispatcher.submit(new TestInstruction(), false, next->{}));
        run(()->dispatcher.cancel());

        Thread.sleep(900);
        assertTrue(connection.sent.isEmpty(), "an instruction was sent after the task ended");
    }

    @Test
    void cancellingStopsTheTaskTimeout() throws Exception {
        run(()->dispatcher.resetTimeout());
        assertNotEquals(-1l, dispatcher.timeoutTimer);

        run(()->dispatcher.cancel());
        assertEquals(-1l, dispatcher.timeoutTimer);
    }

    /**
     * Run the action on the dispatcher's context and wait for it to finish.
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
