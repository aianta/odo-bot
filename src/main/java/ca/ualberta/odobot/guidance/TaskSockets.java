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
 * The OdoX sockets of one task. Each task listens on a port of its own, so the only OdoX that connects is the one in
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
     * @return completes with the task's client once its control and event sockets are connected.
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
     * Accept a websocket on the task's listener. OdoX puts its clientId and the socket's source in the URL, e.g.
     * wss://host/?clientId=&lt;uuid&gt;&amp;source=ControlSocket, so a socket of another client is rejected here, before
     * any message. Older OdoX sends neither; its sockets are checked on their first message, see
     * {@link WebSocketConnection}.
     */
    public void accept(ServerWebSocket socket){
        UUID socketClientId = WebSocketConnection.clientIdFromUri(socket.uri());
        Source source = WebSocketConnection.sourceFromUri(socket.uri());

        if(socketClientId != null && !accepts(socketClientId)){
            log.warn("[{}] Rejecting a websocket of OdoX client {} ({}), which is not this task's.", client.label(), socketClientId, socket.remoteAddress());
            socket.reject(403);
            return;
        }

        new WebSocketConnection(client.vertx(), socket, this, socketClientId, source);
    }

    /**
     * Bind a socket to the task's client. A socket for a source that is already bound replaces it, e.g. when OdoX
     * reconnects.
     *
     * @return the task's client, or null if the socket's clientId is not the task's, in which case the caller closes it.
     */
    public OdoClient bind(UUID id, Source source, WebSocketConnection connection){
        if(!accepts(id)){
            log.warn("[{}] Refusing a {} of OdoX client {}, which is not this task's.", client.label(), source.name, id);
            return null;
        }

        switch (source){
            case EVENT_SOCKET -> client.setEvent(connection);
            case CONTROL_SOCKET -> client.setControl(connection);
            case GUIDANCE_SOCKET -> client.setGuidance(connection);
        }
        log.info("[{}][{}] Bound websocket", client.label(), source.name);

        //The task sends on the control and event sockets. The guidance socket reconnects with every page load, so it is
        //not waited for.
        if(client.getControl().isConnected() && client.getEvent().isConnected() && ready.tryComplete(client)){
            log.info("[{}] OdoX client ready", client.label());
        }
        return client;
    }

    /**
     * Close the task's sockets, and fail {@link #ready} if they never connected.
     */
    public void close(){
        ready.tryFail("OdoX client %s was closed before it connected".formatted(clientId));
        client.getControl().close();
        client.getEvent().close();
        client.getGuidance().close();
    }
}
