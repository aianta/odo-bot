package ca.ualberta.odobot.guidance;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Low-level wrapper around OdoX's socket to OdoBot. There is one per client; its messages are split into channels by
 * {@link OdoClient#route}.
 *
 * <p>OdoX puts its clientId and protocol version in the socket's URL, e.g.
 * {@code wss://host/?clientId=<uuid>&protocol=3}, so the socket is bound to its client when it is accepted, see
 * {@link TaskSockets#accept}.</p>
 *
 * <p>Every message, both ways, is a flat JSON object with {@code v} (the protocol version), {@code channel},
 * {@code type} and {@code id}, plus {@code replyTo} on a reply, {@code cause} on guidance feedback OdoX sends unasked,
 * and the fields of its type. Guidance messages also carry {@code pageId}: the page a message from OdoBot is addressed
 * to, or the page a message from OdoX came from. OdoX answers a message for a page that is gone with NOT_DELIVERED.</p>
 */
public class WebSocketConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);

    /**
     * The version of the protocol OdoBot speaks with OdoX. Sockets of any other version are refused.
     */
    public static final int PROTOCOL_VERSION = 3;

    private static final long PING_PERIOD_MS = 600000; //Send pings every 10 seconds (10,000ms)

    private long pingPongTimer;
    private ServerWebSocket socket;

    private Vertx vertx;

    /**
     * The client this socket belongs to. Null for a placeholder connection.
     */
    private OdoClient client;

    private boolean isConnected = false;

    private Consumer<JsonObject> messageConsumer;

    public WebSocketConnection(){}

    /**
     * @param client the client the socket was accepted for.
     */
    public WebSocketConnection(Vertx vertx, ServerWebSocket socket, OdoClient client){
        this.vertx = vertx; //Get a reference to the vertx instance, we'll need this to ping/pong active sockets.
        this.client = client;
        handleConnection(socket);
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
     * @return how logs name this socket: its client, as far as it is known.
     */
    private String label(){
        return client == null? "unbound" : client.label();
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

    private void onMessage(Buffer buffer){
        JsonObject message;
        try{
            message = buffer.toJsonObject();
        }catch (DecodeException e){
            log.warn("[{}] Dropping a message that isn't a JSON object: {}", label(), e.getMessage());
            return;
        }

        //Events are many and large, so only the other messages are logged.
        if(!Channel.EVENT.name.equals(message.getString("channel"))){
            String encoded = message.encodePrettily();
            log.info("[{}] got message:\n{}", label(), encoded.substring(0, Math.min(encoded.length(), 150)));
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
     * @return the clientId in the query of a websocket URL, e.g. {@code /?clientId=<uuid>&protocol=3}, or null if it has no valid one.
     */
    public static UUID clientIdFromUri(String uri){
        return parseUuid(queryParameter(uri, "clientId"));
    }

    /**
     * @return the protocol version in the query of a websocket URL, or null if it has no valid one.
     */
    public static Integer protocolFromUri(String uri){
        String value = queryParameter(uri, "protocol");
        if(value == null){
            return null;
        }
        try{
            return Integer.parseInt(value);
        }catch (NumberFormatException e){
            return null;
        }
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
