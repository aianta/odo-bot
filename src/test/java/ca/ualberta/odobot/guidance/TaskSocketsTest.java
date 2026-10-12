package ca.ualberta.odobot.guidance;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each task accepts only its own OdoX's sockets, and becomes ready once its control and event sockets are connected.
 */
class TaskSocketsTest {

    /**
     * A connected socket without a network connection behind it.
     */
    static class StubConnection extends WebSocketConnection {
        boolean closed = false;

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static JsonObject task(String evalId){
        return new JsonObject().put("evalId", evalId);
    }

    @Test
    void theClientIsReadyOnceItsControlAndEventSocketsAreBound(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);

        sockets.bind(id, Source.CONTROL_SOCKET, new StubConnection());
        assertFalse(sockets.ready().isComplete(), "the event socket is still missing");

        OdoClient client = sockets.bind(id, Source.EVENT_SOCKET, new StubConnection());
        assertTrue(sockets.ready().succeeded());
        assertSame(client, sockets.ready().result());
        assertEquals(id, client.id());
        assertEquals("eval-1", client.label());
    }

    @Test
    void theGuidanceSocketAloneDoesNotMakeTheClientReady(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);

        sockets.bind(id, Source.GUIDANCE_SOCKET, new StubConnection());
        sockets.bind(id, Source.CONTROL_SOCKET, new StubConnection());

        assertFalse(sockets.ready().isComplete());
    }

    @Test
    void socketsOfAnotherClientAreRefused(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);

        assertNull(sockets.bind(UUID.randomUUID(), Source.CONTROL_SOCKET, new StubConnection()));
        assertNull(sockets.bind(UUID.randomUUID(), Source.EVENT_SOCKET, new StubConnection()));

        assertFalse(sockets.ready().isComplete());
        assertFalse(sockets.client().getControl().isConnected(), "the stray socket was not bound");
    }

    @Test
    void aReconnectedSocketReplacesTheOldOne(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);
        StubConnection first = new StubConnection();
        StubConnection second = new StubConnection();

        sockets.bind(id, Source.EVENT_SOCKET, first);
        sockets.bind(id, Source.EVENT_SOCKET, second);

        assertSame(second, sockets.client().getEvent());
    }

    @Test
    void closingClosesTheSocketsAndFailsAPendingReady(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);
        StubConnection control = new StubConnection();
        sockets.bind(id, Source.CONTROL_SOCKET, control);

        sockets.close();

        assertTrue(control.closed);
        assertTrue(sockets.ready().failed());
    }

    /*
     * Real websockets: each task has a listener of its own on a port the OS picks, as TaskVerticle sets them up.
     */

    private Vertx vertx;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(){
        vertx = Vertx.vertx();
        httpClient = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private int listen(TaskSockets sockets) throws Exception {
        HttpServer server = vertx.createHttpServer(new HttpServerOptions().setHost("localhost").setPort(0))
                .webSocketHandler(sockets::accept);
        return await(server.listen()).actualPort();
    }

    private Future<WebSocket> connect(int port, UUID clientId, Source source){
        String uri = clientId == null? "/" : "/?clientId=%s&source=%s".formatted(clientId, source.name);
        return httpClient.webSocket(port, "localhost", uri);
    }

    @Test
    void eachTaskGetsOnlyItsOwnOdoXOverRealSockets() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        TaskSockets first = new TaskSockets(firstId, task("eval-1"), vertx);
        TaskSockets second = new TaskSockets(secondId, task("eval-2"), vertx);
        int firstPort = listen(first);
        int secondPort = listen(second);
        assertNotEquals(firstPort, secondPort);

        //The second task's OdoX connects to the first task's port by mistake, and is turned away.
        Future<WebSocket> stray = connect(firstPort, secondId, Source.CONTROL_SOCKET);
        waitUntil(stray::isComplete);
        assertTrue(stray.failed(), "a socket of another task's OdoX was accepted");

        //Both OdoX connect to their own task, interleaved.
        await(connect(firstPort, firstId, Source.CONTROL_SOCKET));
        await(connect(secondPort, secondId, Source.CONTROL_SOCKET));
        await(connect(secondPort, secondId, Source.EVENT_SOCKET));
        await(connect(firstPort, firstId, Source.EVENT_SOCKET));

        OdoClient firstClient = await(first.ready());
        OdoClient secondClient = await(second.ready());
        assertEquals(firstId, firstClient.id());
        assertEquals(secondId, secondClient.id());
        assertNotSame(firstClient, secondClient);
    }

    @Test
    void anOlderOdoXIsBoundByItsFirstMessage() throws Exception {
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), vertx);
        int port = listen(sockets);

        WebSocket control = await(connect(port, null, null));
        control.writeTextMessage(new JsonObject().put("clientId", id.toString()).put("source", "ControlSocket").put("type", "HELLO").encode());
        WebSocket event = await(connect(port, null, null));
        event.writeTextMessage(new JsonObject().put("clientId", id.toString()).put("source", "EventSocket").put("type", "HELLO").encode());

        assertEquals(id, await(sockets.ready()).id());
    }

    @Test
    void anOlderOdoXOfAnotherTaskIsClosedOnItsFirstMessage() throws Exception {
        TaskSockets sockets = new TaskSockets(UUID.randomUUID(), task("eval-1"), vertx);
        int port = listen(sockets);

        WebSocket socket = await(connect(port, null, null));
        boolean[] closed = {false};
        socket.closeHandler(v->closed[0] = true);
        socket.writeTextMessage(new JsonObject().put("clientId", UUID.randomUUID().toString()).put("source", "ControlSocket").put("type", "HELLO").encode());

        waitUntil(()->closed[0]);
        assertFalse(sockets.ready().isComplete());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (!condition.getAsBoolean()){
            if(System.currentTimeMillis() > deadline){
                fail("timed out waiting");
            }
            Thread.sleep(20);
        }
    }
}
