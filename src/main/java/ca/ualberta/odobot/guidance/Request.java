package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.ControlConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.EventConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

public class Request {

    private static final Logger log = LoggerFactory.getLogger(Request.class);

    //Request Manager
    private RequestManager requestManager = null;

    //Connection Managers
    private ControlConnectionManager controlConnectionManager = null;
    private EventConnectionManager eventConnectionManager = null;
    private GuidanceConnectionManager guidanceConnectionManager = null;

    //WebSocket Connections
    private WebSocketConnection control = new WebSocketConnection();
    private WebSocketConnection guidance = new WebSocketConnection();
    private WebSocketConnection event = new WebSocketConnection();

    private Set<NavPath> navigationPaths = null;

    private UUID id;

    private String targetNode;

    public String getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(String targetNode) {
        this.targetNode = targetNode;
    }

    public UUID id(){
        return id;
    }

    public WebSocketConnection getControl() {
        return control;
    }

    public void setControl(WebSocketConnection control) {
        this.control = control;
        controlConnectionManager.updateConnection(control);
    }

    public WebSocketConnection getGuidance() {
        return guidance;
    }

    public void setGuidance(WebSocketConnection guidance) {

        this.guidance = guidance;
        guidanceConnectionManager.updateConnection(guidance);
    }

    public WebSocketConnection getEvent() {
        return event;
    }

    public void setEvent(WebSocketConnection event) {
        this.event = event;
        eventConnectionManager.updateConnection(event);
    }

    public Request(UUID id){
        this.id = id;

        this.controlConnectionManager = new ControlConnectionManager(this);
        this.requestManager = new RequestManager(this); //Request Manager must be initalized after control connection manager
        this.eventConnectionManager = new EventConnectionManager(this); //Event connection manager must be called after RequestManager is bound to the request.
        this.guidanceConnectionManager = new GuidanceConnectionManager(this);


    }

    public ControlConnectionManager getControlConnectionManager() {
        return controlConnectionManager;
    }

    public EventConnectionManager getEventConnectionManager() {
        return eventConnectionManager;
    }

    public GuidanceConnectionManager getGuidanceConnectionManager() {
        return guidanceConnectionManager;
    }

    public RequestManager getRequestManager() {
        return requestManager;
    }

    public void setRequestManager(RequestManager requestManager) {
        this.requestManager = requestManager;
    }
}
