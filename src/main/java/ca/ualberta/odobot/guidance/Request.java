package ca.ualberta.odobot.guidance;

import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class Request {

    private static final Logger log = LoggerFactory.getLogger(Request.class);

    private WebSocketConnection control = new WebSocketConnection();
    private WebSocketConnection guidance = new WebSocketConnection();
    private WebSocketConnection event = new WebSocketConnection();

    private UUID id;

    private String targetNode;

    public UUID id(){
        return id;
    }

    public WebSocketConnection getControl() {
        return control;
    }

    public void setControl(WebSocketConnection control) {
        this.control = control;
    }

    public WebSocketConnection getGuidance() {
        return guidance;
    }

    public void setGuidance(WebSocketConnection guidance) {
        this.guidance = guidance;
    }

    public WebSocketConnection getEvent() {
        return event;
    }

    public void setEvent(WebSocketConnection event) {
        this.event = event;
    }

    public Request(UUID id){
        this.id = id;
    }

    public void processWebSocketConnection(ServerWebSocket socket, Source source){
        switch (source){
            case EVENT_SOCKET -> event.handleConnection(socket);
            case GUIDANCE_SOCKET -> event.handleConnection(socket);
            case CONTROL_SOCKET -> event.handleConnection(socket);
        }
    }



}
