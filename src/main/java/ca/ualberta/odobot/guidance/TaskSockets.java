package ca.ualberta.odobot.guidance;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The OdoX socket of one task. Each task listens on a port of its own, so the only OdoX that connects is the one in
 * the task's browser. Its clientId, the extension instance id, is chosen before the browser starts, and sockets with
 * any other clientId are refused.
 *
 * <p>Used on the task's context only.</p>
 */
public final class TaskSockets {

    private static final Logger log = LoggerFactory.getLogger(TaskSockets.class);

    private final UUID clientId;
    private final OdoClient client;
    private final Promise<OdoClient> ready = Promise.promise();

    /**
     * @param clientId the extension instance id of the task's OdoX.
     * @param taskContext describes the task, e.g. its evalId, for the client's logs.
     * @param vertx the Vert.x instance the task's sockets are accepted on.
     */
    public TaskSockets(UUID clientId, JsonObject taskContext, Vertx vertx){
        this.clientId = clientId;
        this.client = new OdoClient(clientId, taskContext, vertx);
    }

    public UUID clientId(){
        return clientId;
    }

    public OdoClient client(){
        return client;
    }

    /**
     * @return completes with the task's client once its OdoX has connected.
     */
    public Future<OdoClient> ready(){
        return ready.future();
    }

    /**
     * @return true if a socket with the given clientId belongs to this task.
     */
    public boolean accepts(UUID id){
        return clientId.equals(id);
    }

    /**
     * Accept a websocket on the task's listener. OdoX puts its clientId and protocol version in the URL, e.g.
     * wss://host/?clientId=&lt;uuid&gt;&amp;protocol=3, so a socket of another client, or of an OdoX that speaks another
     * protocol, is rejected here, before any message.
     */
    public void accept(ServerWebSocket socket){
        UUID socketClientId = WebSocketConnection.clientIdFromUri(socket.uri());
        Integer protocol = WebSocketConnection.protocolFromUri(socket.uri());

        if(socketClientId == null || !accepts(socketClientId)){
            log.warn("[{}] Rejecting a websocket of OdoX client {} ({}), which is not this task's.", client.label(), socketClientId, socket.remoteAddress());
            socket.reject(403);
            return;
        }

        if(protocol == null || protocol != WebSocketConnection.PROTOCOL_VERSION){
            log.warn("[{}] Rejecting a websocket of OdoX speaking protocol {}, OdoBot speaks protocol {}. Is OdoX out of date?",
                    client.label(), protocol, WebSocketConnection.PROTOCOL_VERSION);
            socket.reject(403);
            return;
        }

        bind(new WebSocketConnection(client.vertx(), socket, client));
    }

    /**
     * Bind a socket to the task's client. It replaces any socket bound before, e.g. when OdoX reconnects.
     *
     * @return the task's client.
     */
    public OdoClient bind(WebSocketConnection connection){
        client.setSocket(connection);
        log.info("[{}] Bound websocket", client.label());

        if(ready.tryComplete(client)){
            log.info("[{}] OdoX client ready", client.label());
        }
        return client;
    }

    /**
     * Close the task's socket, and fail {@link #ready} if it never connected.
     */
    public void close(){
        ready.tryFail("OdoX client %s was closed before it connected".formatted(clientId));
        client.getSocket().close();
    }
}
