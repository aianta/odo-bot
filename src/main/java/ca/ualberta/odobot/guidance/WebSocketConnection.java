package ca.ualberta.odobot.guidance;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

public class WebSocketConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);

    private ServerWebSocket socket;


    static Map<UUID, Request> requestMap = new HashMap<>();

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
        log.info("[{}][{}] Connection Closed", boundRequest.id().toString(), boundSource.name);
    }

    public void send(JsonObject data){
        socket.writeTextMessage(data.encode(), result->log.info("data sent on socket!"));
    }

    private void onMessage(Buffer buffer){
        JsonObject message = buffer.toJsonObject();

        if(!isBound){ //Associate this WebSocket connection with the appropriate paths request.
            UUID pathsRequestId = UUID.fromString(message.getString("pathsRequestId"));

            if(!requestMap.containsKey(pathsRequestId)){
                Request request = new Request(pathsRequestId);
                requestMap.put(pathsRequestId, request);
            }

            //Set the bound request and source
            boundRequest = requestMap.get(pathsRequestId);
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

        log.info("[{}][{}] got message:\n{}", boundRequest.id().toString(), boundSource.name, message.encodePrettily());
        messageConsumer.accept(message);
    }

    public void setMessageConsumer(Consumer<JsonObject> consumer){
        this.messageConsumer = consumer;
    }

}
