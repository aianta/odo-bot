package ca.ualberta.odobot.guidance;

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

    private ServerWebSocket socket;


    static Map<String, Request> requestMap = new HashMap<>();

    private boolean isBound = false;

    private Request boundRequest = null;

    private Source boundSource = null;

    private boolean isConnected = false;

    private Consumer<JsonObject> messageConsumer;

    public WebSocketConnection(){}

    public WebSocketConnection(ServerWebSocket socket){
        handleConnection(socket);
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
    }

    private void onError(Throwable error){
        String errLine = "[%s][%s] WebSocket Error %s".formatted(boundRequest.id().toString(), boundSource.name, error.getMessage() );
        log.error(errLine, error);
    }

    private void onClose(Void event){
        this.isConnected = false;
        if(boundRequest != null){
            log.info("[{}][{}] Connection Closed", boundRequest.id().toString(), boundSource.name);
        }else{
            log.info("[{}] Connection Closed", boundSource.name);
        }

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
     * This method works to associate the websocket with the appropriate request upon receiving the first message from the client.
     * The appropriate request is identified by a 'pathsRequestId' field.
     *
     * Additionally, each requests has {@link Request#control}, {@link Request#event}, and {@link Request#guidance} fields for {@link WebSocketConnection} to
     * be assigned to. This method reads the 'source' field in the in initial message to appropriately assign itself to the corresponding Request.
     *
     * Requests are held in a static map {@link WebSocketConnection#requestMap}.
     *
     * All messages from the client are expected to have, at minimum, the 'source' and 'pathsRequestId' field.
     *
     * @param buffer
     */
    private void onMessage(Buffer buffer){
        JsonObject message = buffer.toJsonObject();

        if(!isBound){ //Associate this WebSocket connection with the appropriate paths request.
            UUID pathsRequestId = UUID.fromString(message.getString("pathsRequestId"));
            printRequestMap();
            if(!requestMap.containsKey(pathsRequestId.toString())){
                log.info("Making new request with id: {}", pathsRequestId.toString());
                Request request = new Request(pathsRequestId);
                requestMap.put(pathsRequestId.toString(), request);
            }else{
                log.info("Binding websocket to existing request! {}", pathsRequestId.toString());
            }

            //Set the bound request and source
            boundRequest = requestMap.get(pathsRequestId.toString());
            if(boundRequest == null){
                return;
            }
            boundSource = Source.getSourceByName(message.getString("source"));

            switch (boundSource){
                case EVENT_SOCKET -> boundRequest.setEvent(this);
                case CONTROL_SOCKET -> boundRequest.setControl(this);
                case GUIDANCE_SOCKET -> boundRequest.setGuidance(this);
            }

            isBound = true;

        }

        log.info("[{}][{}] got message:\n{}", boundRequest.id().toString(), boundSource.name, message.encodePrettily().substring(0, Math.min(message.encodePrettily().length(), 150)));
        messageConsumer.accept(message);
    }

    public void setMessageConsumer(Consumer<JsonObject> consumer){
        this.messageConsumer = consumer;
    }


    public void printRequestMap(){
        log.info("Request map [size: {}]",requestMap.size());
        requestMap.entrySet().forEach(entry->log.info("{} - {}", entry.getKey(), entry.getValue()));
    }
}
