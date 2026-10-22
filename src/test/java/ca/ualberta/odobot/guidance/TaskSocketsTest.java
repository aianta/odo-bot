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
 * Each task accepts only its own OdoX's socket, of the protocol version OdoBot speaks, and becomes ready once it is
 * connected.
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
    void theClientIsReadyOnceItsSocketIsBound(){
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), null);
        assertFalse(sockets.ready().isComplete());

        OdoClient client = sockets.bind(new StubConnection());
        assertTrue(sockets.ready().succeeded());
        assertSame(client, sockets.ready().result());
        assertEquals(id, client.id());
        assertEquals("eval-1", client.label());
    }

    @Test
    void aReconnectedSocketReplacesTheOldOne(){
        TaskSockets sockets = new TaskSockets(UUID.randomUUID(), task("eval-1"), null);
        StubConnection first = new StubConnection();
        StubConnection second = new StubConnection();

        sockets.bind(first);
        sockets.bind(second);

        assertSame(second, sockets.client().getSocket());
    }

    @Test
    void closingClosesTheSocket(){
        TaskSockets sockets = new TaskSockets(UUID.randomUUID(), task("eval-1"), null);
        StubConnection socket = new StubConnection();
        sockets.bind(socket);

        sockets.close();

        assertTrue(socket.closed);
    }

    @Test
    void closingFailsAPendingReady(){
        TaskSockets sockets = new TaskSockets(UUID.randomUUID(), task("eval-1"), null);

        sockets.close();

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

    private Future<WebSocket> connect(int port, String uri){
        return httpClient.webSocket(port, "localhost", uri);
    }

    private Future<WebSocket> connect(int port, UUID clientId){
        return connect(port, "/?clientId=%s&protocol=%d".formatted(clientId, WebSocketConnection.PROTOCOL_VERSION));
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
        Future<WebSocket> stray = connect(firstPort, secondId);
        waitUntil(stray::isComplete);
        assertTrue(stray.failed(), "a socket of another task's OdoX was accepted");
        assertFalse(first.ready().isComplete());

        //Both OdoX connect to their own task.
        await(connect(secondPort, secondId));
        await(connect(firstPort, firstId));

        OdoClient firstClient = await(first.ready());
        OdoClient secondClient = await(second.ready());
        assertEquals(firstId, firstClient.id());
        assertEquals(secondId, secondClient.id());
        assertNotSame(firstClient, secondClient);
    }

    @Test
    void aSocketWithoutAClientIdIsRefused() throws Exception {
        TaskSockets sockets = new TaskSockets(UUID.randomUUID(), task("eval-1"), vertx);
        int port = listen(sockets);

        Future<WebSocket> socket = connect(port, "/?protocol=%d".formatted(WebSocketConnection.PROTOCOL_VERSION));
        waitUntil(socket::isComplete);

        assertTrue(socket.failed());
        assertFalse(sockets.ready().isComplete());
    }

    @Test
    void aSocketOfAnotherProtocolIsRefused() throws Exception {
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), vertx);
        int port = listen(sockets);

        //The three-socket OdoX sent a source instead of a protocol version.
        Future<WebSocket> old = connect(port, "/?clientId=%s&source=ControlSocket".formatted(id));
        Future<WebSocket> other = connect(port, "/?clientId=%s&protocol=1".formatted(id));
        waitUntil(()->old.isComplete() && other.isComplete());

        assertTrue(old.failed());
        assertTrue(other.failed());
        assertFalse(sockets.ready().isComplete());
    }

    @Test
    void messagesReachTheirChannelOverARealSocket() throws Exception {
        UUID id = UUID.randomUUID();
        TaskSockets sockets = new TaskSockets(id, task("eval-1"), vertx);
        int port = listen(sockets);

        WebSocket socket = await(connect(port, id));
        OdoClient client = await(sockets.ready());
        assertFalse(client.isPageReady());

        socket.writeTextMessage(new JsonObject()
                .put("v", WebSocketConnection.PROTOCOL_VERSION)
                .put("channel", "guidance")
                .put("type", "PAGE_READY")
                .put("id", UUID.randomUUID().toString())
                .put("pageId", "page-1")
                .encode());

        waitUntil(client::isPageReady);
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
