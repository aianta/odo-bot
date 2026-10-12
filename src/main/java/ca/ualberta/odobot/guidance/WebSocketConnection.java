package ca.ualberta.odobot.guidance;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Low-level wrapper around WebSocket connections.
 *
 *
 */
public class WebSocketConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);

    private static final long PING_PERIOD_MS = 600000; //Send pings every 10 seconds (10,000ms)

    private long pingPongTimer;
    private ServerWebSocket socket;

    private Vertx vertx;

    /**
     * The task this socket was accepted for. Null for a placeholder connection.
     */
    private TaskSockets sockets;
    private boolean isBound = false;

    private OdoClient boundClient = null;

    private Source boundSource = null;

    private boolean isConnected = false;

    private Consumer<JsonObject> messageConsumer;

    public WebSocketConnection(){}

    /**
     * @param sockets the task the socket was accepted for.
     * @param clientId the clientId from the socket's URL, or null if it had none. Then the socket is bound on its first message.
     * @param source the source from the socket's URL, or null if it had none.
     */
    public WebSocketConnection(Vertx vertx, ServerWebSocket socket, TaskSockets sockets, UUID clientId, Source source){
        this.vertx = vertx; //Get a reference to the vertx instance, we'll need this to ping/pong active sockets.
        this.sockets = sockets;
        handleConnection(socket);
        if(clientId != null && source != null){
            bind(clientId, source);
        }
    }

    /**
     * @return the Vert.x instance this socket was accepted on, or null for a placeholder connection.
     */
    public Vertx vertx(){
        return vertx;
    }

    public void close(){
        if(socket != null && !socket.isClosed()){
            socket.close();
        }
    }

    public void handleConnection(ServerWebSocket socket){
        if(isConnected){
            log.warn("Handling new websocket even though, old websocket is still connected! This probably shouldn't happen...");
        }
        this.socket = socket;
        this.socket.handler(this::onMessage);
        this.socket.closeHandler(this::onClose);
        this.socket.exceptionHandler(this::onError);
        isConnected = true;

        //Setup ping/pong to keep the connection alive.
        pingPongTimer = vertx.setPeriodic(PING_PERIOD_MS, timerId->{
            if(this.socket == null || this.socket.isClosed()){
                vertx.cancelTimer(timerId);
            }

           this.socket.writePing(Buffer.buffer("OdoBot Ping"), pingWritten->{
               //This handler is called when the ping was successfully written to the socket.
               log.info("[{}] Websocket ping sent!", label());
           });
        });

        this.socket.pongHandler(pong->{
            log.info("[{}] Got pong!", label());
        });

    }

    /**
     * @return how logs name this socket: its client and source, as far as they are known.
     */
    private String label(){
        return "%s][%s".formatted(boundClient == null? "unbound" : boundClient.label(), boundSource == null? "?" : boundSource.name);
    }

    private void onError(Throwable error){
        log.error("[{}] WebSocket Error {}", label(), error.getMessage(), error);

        vertx.cancelTimer(pingPongTimer);

    }

    private void onClose(Void event){
        this.isConnected = false;
        log.info("[{}] Connection Closed", label());

        vertx.cancelTimer(pingPongTimer);

    }

    public void send(String data){
        socket.writeTextMessage(data, result->log.info("data sent on socket!"));
    }

    public void send(JsonObject data){
        socket.writeTextMessage(data.encode(), result->log.info("data sent on socket!"));
    }

    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Bind this socket to the task's client, see {@link TaskSockets#bind}. A socket with another clientId is closed.
     */
    private void bind(UUID clientId, Source source){
        OdoClient client = sockets.bind(clientId, source, this);
        if(client == null){
            close();
            return;
        }
        boundSource = source;
        boundClient = client;
        isBound = true;
    }

    /**
     * Messages from the extension carry, at minimum, the 'source' and 'clientId' fields. A socket whose URL had no clientId
     * (an older OdoX) is bound to its client by its first message, see {@link TaskSockets#bind}. A socket whose clientId
     * is not the task's is closed.
     *
     * @param buffer
     */
    private void onMessage(Buffer buffer){
        JsonObject message = buffer.toJsonObject();

        if(!isBound){ //Associate this WebSocket connection with its client.
            UUID clientId = parseUuid(message.getString("clientId"));
            Source source = Source.fromName(message.getString("source"));

            if(clientId == null || source == null){
                log.warn("Closing websocket, its first message has no valid clientId and source: {} {}", message.getString("clientId"), message.getString("source"));
                close();
                return;
            }

            bind(clientId, source);
            if(!isBound){
                return;
            }
            log.info("Underlying message type: {} ", message.getString("type"));

        }else if(message.containsKey("clientId") && !boundClient.id().toString().equalsIgnoreCase(message.getString("clientId"))){
            log.warn("[{}] Message has clientId {}, keeping the socket bound to {}", label(), message.getString("clientId"), boundClient.id());
        }

        if(boundSource != Source.EVENT_SOCKET){
            log.info("[{}] got message:\n{}", label(), message.encodePrettily().substring(0, Math.min(message.encodePrettily().length(), 150)));
        }

        if(messageConsumer == null){
            log.warn("[{}] No consumer for message of type {}, dropping it", label(), message.getString("type"));
            return;
        }
        messageConsumer.accept(message);
    }

    public void setMessageConsumer(Consumer<JsonObject> consumer){
        this.messageConsumer = consumer;
    }

    /**
     * @return the clientId in the query of a websocket URL, e.g. {@code /?clientId=<uuid>&source=ControlSocket}, or null if it has no valid one.
     */
    public static UUID clientIdFromUri(String uri){
        return parseUuid(queryParameter(uri, "clientId"));
    }

    /**
     * @return the source in the query of a websocket URL, or null if it has no valid one.
     */
    public static Source sourceFromUri(String uri){
        return Source.fromName(queryParameter(uri, "source"));
    }

    private static String queryParameter(String uri, String name){
        if(uri == null){
            return null;
        }
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return values == null || values.isEmpty()? null : values.get(0);
    }

    private static UUID parseUuid(String value){
        if(value == null){
            return null;
        }
        try{
            return UUID.fromString(value);
        }catch (IllegalArgumentException e){
            return null;
        }
    }
}
