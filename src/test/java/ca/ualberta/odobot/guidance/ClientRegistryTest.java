package ca.ualberta.odobot.guidance;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sockets are bound to the client of the task that expects their clientId, and a task is only given its own client.
 */
class ClientRegistryTest {

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

    private final List<UUID> ids = new ArrayList<>();

    private UUID newId(){
        UUID id = UUID.randomUUID();
        ids.add(id);
        return id;
    }

    @AfterEach
    void tearDown(){
        ids.forEach(ClientRegistry::release);
        ClientRegistry.setStrict(false);
    }

    private static JsonObject task(String evalId){
        return new JsonObject().put("evalId", evalId);
    }

    @Test
    void theClientIsReadyOnceItsControlAndEventSocketsAreBound(){
        UUID id = newId();
        Future<OdoClient> ready = ClientRegistry.expect(id, task("eval-1"));

        ClientRegistry.bind(id, Source.CONTROL_SOCKET, new StubConnection());
        assertFalse(ready.isComplete(), "the event socket is still missing");

        OdoClient client = ClientRegistry.bind(id, Source.EVENT_SOCKET, new StubConnection());
        assertTrue(ready.succeeded());
        assertSame(client, ready.result());
        assertEquals(id, client.id());
        assertEquals("eval-1", client.label());
    }

    @Test
    void theGuidanceSocketAloneDoesNotMakeTheClientReady(){
        UUID id = newId();
        Future<OdoClient> ready = ClientRegistry.expect(id, task("eval-1"));

        ClientRegistry.bind(id, Source.GUIDANCE_SOCKET, new StubConnection());
        ClientRegistry.bind(id, Source.CONTROL_SOCKET, new StubConnection());

        assertFalse(ready.isComplete());
    }

    @Test
    void aTaskIsOnlyGivenTheClientWithItsOwnId(){
        UUID first = newId();
        UUID second = newId();
        UUID stray = newId();
        Future<OdoClient> firstReady = ClientRegistry.expect(first, task("eval-1"));
        Future<OdoClient> secondReady = ClientRegistry.expect(second, task("eval-2"));

        ClientRegistry.bind(stray, Source.CONTROL_SOCKET, new StubConnection());
        ClientRegistry.bind(stray, Source.EVENT_SOCKET, new StubConnection());
        ClientRegistry.bind(second, Source.CONTROL_SOCKET, new StubConnection());
        ClientRegistry.bind(second, Source.EVENT_SOCKET, new StubConnection());

        assertFalse(firstReady.isComplete());
        assertTrue(secondReady.succeeded());
        assertEquals(second, secondReady.result().id());
        assertNull(ClientRegistry.get(stray).taskContext(), "no task expects the stray client");
    }

    @Test
    void releaseOnlyClosesItsOwnClient(){
        UUID first = newId();
        UUID second = newId();
        ClientRegistry.expect(first, task("eval-1"));
        ClientRegistry.expect(second, task("eval-2"));
        StubConnection firstControl = new StubConnection();
        StubConnection secondControl = new StubConnection();
        ClientRegistry.bind(first, Source.CONTROL_SOCKET, firstControl);
        ClientRegistry.bind(second, Source.CONTROL_SOCKET, secondControl);

        ClientRegistry.release(first);

        assertTrue(firstControl.closed);
        assertNull(ClientRegistry.get(first));
        assertFalse(ClientRegistry.isExpected(first));
        assertFalse(secondControl.closed);
        assertNotNull(ClientRegistry.get(second));
        assertTrue(ClientRegistry.isExpected(second));
    }

    @Test
    void releaseFailsAPendingExpectation(){
        UUID id = newId();
        Future<OdoClient> ready = ClientRegistry.expect(id, task("eval-1"));

        ClientRegistry.release(id);

        assertTrue(ready.failed());
    }

    @Test
    void expectingAnIdAgainStartsOver(){
        UUID id = newId();
        Future<OdoClient> firstAttempt = ClientRegistry.expect(id, task("eval-1"));
        StubConnection oldControl = new StubConnection();
        ClientRegistry.bind(id, Source.CONTROL_SOCKET, oldControl);

        Future<OdoClient> secondAttempt = ClientRegistry.expect(id, task("eval-1"));

        assertTrue(firstAttempt.failed());
        assertTrue(oldControl.closed, "the first attempt's sockets are closed");
        assertNull(ClientRegistry.get(id));

        ClientRegistry.bind(id, Source.CONTROL_SOCKET, new StubConnection());
        ClientRegistry.bind(id, Source.EVENT_SOCKET, new StubConnection());
        assertTrue(secondAttempt.succeeded());
    }

    @Test
    void theHandshakeUrlCarriesTheClientIdAndSource(){
        UUID id = UUID.randomUUID();

        assertEquals(id, WebSocketConnection.clientIdFromUri("/?clientId=" + id + "&source=ControlSocket"));
        assertEquals(Source.CONTROL_SOCKET, WebSocketConnection.sourceFromUri("/?clientId=" + id + "&source=ControlSocket"));
        assertEquals(Source.GUIDANCE_SOCKET, WebSocketConnection.sourceFromUri("/?source=GuidanceSocket&clientId=" + id));
    }

    @Test
    void aHandshakeUrlWithoutAValidClientIdOrSourceGivesNull(){
        assertNull(WebSocketConnection.clientIdFromUri("/"));
        assertNull(WebSocketConnection.clientIdFromUri(null));
        assertNull(WebSocketConnection.clientIdFromUri("/?clientId=not-a-uuid&source=ControlSocket"));
        assertNull(WebSocketConnection.sourceFromUri("/"));
        assertNull(WebSocketConnection.sourceFromUri("/?clientId=" + UUID.randomUUID() + "&source=OtherSocket"));
    }
}
