package ca.ualberta.odobot.guidance;

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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OdoX's messages go to the manager of their channel, and replies complete the request they answer, by id.
 */
class ChannelRoutingTest {

    /**
     * A connected socket that records what is sent over it.
     */
    public static class RecordingSocket extends WebSocketConnection {
        public final List<JsonObject> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(String data) {
            sent.add(new JsonObject(data));
        }

        /**
         * @return the messages sent of the given type, in order.
         */
        public List<JsonObject> sent(String type){
            return sent.stream().filter(message->type.equals(message.getString("type"))).toList();
        }
    }

    static final UUID EXECUTION_ID = UUID.randomUUID();

    /**
     * A client whose request manager has an execution id, as during a task.
     */
    static OdoClient client(Vertx vertx){
        OdoClient client = new OdoClient(UUID.randomUUID(), vertx);
        client.setRequestManager(new RequestManager(client){
            @Override
            public UUID getExecutionId() {
                return EXECUTION_ID;
            }

            @Override
            public long getTimeout() {
                return 60000;
            }
        });
        return client;
    }

    static JsonObject message(String channel, String type){
        return new JsonObject()
                .put("v", WebSocketConnection.PROTOCOL_VERSION)
                .put("channel", channel)
                .put("type", type)
                .put("id", UUID.randomUUID().toString());
    }

    static JsonObject reply(JsonObject request, String type){
        return message(request.getString("channel"), type).put("replyTo", request.getString("id"));
    }

    private Vertx vertx;
    private Context context;
    private OdoClient client;
    private final RecordingSocket socket = new RecordingSocket();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        client = client(vertx);
        run(()->client.setSocket(socket));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void messagesGoToTheManagerOfTheirChannel() throws Exception {
        //PAGE_READY is a guidance message; on the event channel it means nothing.
        run(()->client.route(message("event", "PAGE_READY")));
        assertFalse(client.isPageReady());

        run(()->client.route(message("guidance", "PAGE_READY")));
        assertTrue(client.isPageReady());
    }

    @Test
    void messagesOnAnUnknownChannelAreDropped() throws Exception {
        run(()->client.route(message("control", "PAGE_READY")));
        run(()->client.route(new JsonObject().put("type", "PAGE_READY")));

        assertFalse(client.isPageReady());
    }

    @Test
    void sentMessagesAreStamped() throws Exception {
        run(()->client.getEventConnectionManager().startTransmitting());

        JsonObject sent = socket.sent("START_TRANSMISSION").get(0);
        assertEquals(WebSocketConnection.PROTOCOL_VERSION, sent.getInteger("v"));
        assertEquals("event", sent.getString("channel"));
        assertNotNull(sent.getString("id"));
    }

    @Test
    void aReplyCompletesTheRequestItAnswersEvenWhenTwoOfTheSameTypeOverlap() throws Exception {
        Future<Void> first = call(()->client.getEventConnectionManager().startTransmitting());
        Future<Void> second = call(()->client.getEventConnectionManager().startTransmitting());
        List<JsonObject> requests = socket.sent("START_TRANSMISSION");
        assertEquals(2, requests.size());

        run(()->client.route(reply(requests.get(1), "TRANSMISSION_STARTED")));
        assertTrue(second.succeeded());
        assertFalse(first.isComplete(), "the reply to the second request completed the first");

        run(()->client.route(reply(requests.get(0), "TRANSMISSION_STARTED")));
        assertTrue(first.succeeded());
    }

    @Test
    void aReplyNoRequestIsWaitingForIsDropped() throws Exception {
        //Not handled as an ordinary PAGE_READY either.
        run(()->client.route(message("guidance", "PAGE_READY").put("replyTo", UUID.randomUUID().toString())));

        assertFalse(client.isPageReady());
    }

    @Test
    void theEndOfTheTaskFailsTheRequestsStillWaiting() throws Exception {
        run(()->client.route(message("guidance", "PAGE_READY")));
        Future<JsonObject> clear = call(()->client.getGuidanceConnectionManager().clearNavigationOptions());
        assertFalse(clear.isComplete());

        run(()->client.getRequestManager().getDispatcher().cancel());

        assertTrue(clear.failed());
        //A late reply is dropped.
        run(()->client.route(reply(socket.sent("CLEAR_NAVIGATION_OPTIONS").get(0), "CLEAR_NAVIGATION_OPTIONS_RESULT")));
    }

    @Test
    void theAlternateXpathConfirmationAnswersTheRegistration() throws Exception {
        run(()->client.route(message("guidance", "PAGE_READY")));
        JsonObject registration = message("guidance", "REGISTER_ALTERNATE_XPATH")
                .put("cause", UUID.randomUUID().toString())
                .put("sourceNodeId", UUID.randomUUID().toString())
                .put("alternateXpath", "/html/body/div[2]");

        run(()->client.route(registration));

        JsonObject confirmation = socket.sent("ALTERNATE_XPATH_CONFIRMED").get(0);
        assertEquals(registration.getString("id"), confirmation.getString("replyTo"));
        assertEquals("/html/body/div[2]", confirmation.getString("alternateXpath"));
    }

    private <T> Future<T> call(Supplier<Future<T>> action) throws Exception {
        AtomicReference<Future<T>> result = new AtomicReference<>();
        run(()->result.set(action.get()));
        return result.get();
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
}
