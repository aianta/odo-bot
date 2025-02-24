package ca.ualberta.odobot.guidance;

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

    private static final long PING_PERIOD_MS = 10000; //Send pings every 10 seconds (10,000ms)

    private long pingPongTimer;
    private ServerWebSocket socket;

    public static Map<UUID, OdoClient> clientMap = new HashMap<>();

    private Vertx vertx;
    private boolean isBound = false;

    private OdoClient boundClient = null;

    private Source boundSource = null;

    private boolean isConnected = false;

    private Consumer<JsonObject> messageConsumer;

    public WebSocketConnection(){}

    public WebSocketConnection(Vertx vertx, ServerWebSocket socket){
        this.vertx = vertx; //Get a reference to the vertx instance, we'll need this to ping/pong active sockets.
        handleConnection(socket);
    }

    public void close(){
        socket.close();
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
               if(boundClient != null && boundSource != null){
                   log.info("[%s][%s] Websocket ping sent!".formatted(boundClient.id().toString(), boundSource.name));
               }else{
                   log.info("Ping sent!");
               }
           });
        });

        this.socket.pongHandler(pong->{
            if(boundClient != null && boundSource != null){
                log.info("[%s][%s] Got pong!".formatted(boundClient.id().toString(), boundSource.name));
            }else{
                log.info("Got pong!");
            }
        });

    }

    private void onError(Throwable error){
        String errLine = "[%s][%s] WebSocket Error %s".formatted(boundClient.id().toString(), boundSource.name, error.getMessage() );
        log.error(errLine, error);

        vertx.cancelTimer(pingPongTimer);

    }

    private void onClose(Void event){
        this.isConnected = false;
        if(boundClient != null){
            log.info("[{}][{}] Connection Closed", boundClient.id().toString(), boundSource.name);
        }else{
            log.info("[{}] Connection Closed", boundSource.name);
        }

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
     * This method works to associate the websocket with the appropriate client upon receiving the first message from the extension.
     * The appropriate client is identified by a 'clientId' field.
     *
     * Additionally, each client has {@link OdoClient#control}, {@link OdoClient#event}, and {@link OdoClient#guidance} fields for {@link WebSocketConnection} to
     * be assigned to. This method reads the 'source' field in the in initial message to appropriately assign itself to the corresponding field.
     *
     * Clients are held in a static map {@link WebSocketConnection#clientMap}.
     *
     * All messages from the extension are expected to have, at minimum, the 'source' and 'clientId' field.
     *
     * @param buffer
     */
    private void onMessage(Buffer buffer){
        JsonObject message = buffer.toJsonObject();

        if(!isBound){ //Associate this WebSocket connection with the appropriate paths request.
            UUID clientId = UUID.fromString(message.getString("clientId"));

            if(!clientMap.containsKey(clientId)){
                log.info("Registering new OdoClient {}", clientId.toString());
                OdoClient client = new OdoClient(clientId);
                clientMap.put(clientId, client);
            }else{
                log.info("Binding websocket to existing OdoClient {}", clientId.toString());
            }

            //Set the bound client and source
            printClientMap();

            boundClient = clientMap.get(clientId);

            //Set the bound client and source
            boundSource = Source.getSourceByName(message.getString("source"));

            switch (boundSource){
                case EVENT_SOCKET -> boundClient.setEvent(this);
                case CONTROL_SOCKET -> boundClient.setControl(this);
                case GUIDANCE_SOCKET -> boundClient.setGuidance(this);
            }

            isBound = true;
            log.info("Underlying message type: {} ", message.getString("type"));

        }
//        if(true){
        if(boundSource != Source.EVENT_SOCKET){
            log.info("[{}][{}] got message:\n{}", boundClient.id().toString(), boundSource.name, message.encodePrettily().substring(0, Math.min(message.encodePrettily().length(), 150)));
        }
        messageConsumer.accept(message);
    }

    public void setMessageConsumer(Consumer<JsonObject> consumer){
        this.messageConsumer = consumer;
    }


    public void printClientMap(){
        log.info("Client map [size: {}]",clientMap.size());
        clientMap.entrySet().forEach(entry->log.info("{} - {}", entry.getKey(), entry.getValue()));
    }
}
