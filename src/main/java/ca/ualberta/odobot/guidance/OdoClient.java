package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.ControlConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.EventConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OdoClient {

    private static final Logger log = LoggerFactory.getLogger(OdoClient.class);

    private UUID id;

    /**
     * The task this client was created for, as given to {@link ClientRegistry#expect}. Null if no task expects it.
     */
    private JsonObject taskContext;

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

    public JsonObject statusReport(){
        JsonObject report = new JsonObject()
                .put("id", id().toString())
                .put("task", taskContext)
                .put("ControlSocket", control.isConnected()?"connected":"disconnected")
                .put("GuidanceSocket", guidance.isConnected()?"connected":"disconnected")
                .put("EventSocket", event.isConnected()?"connected":"disconnected");

        return report;
    }

    public UUID id(){
        return this.id;
    }
    /**
     * @return the task this client was created for, or null if no task expects it.
     */
    public JsonObject taskContext(){
        return taskContext;
    }

    /**
     * @return how logs name this client: its task's evalId if it has one, otherwise its id.
     */
    public String label(){
        if(taskContext != null && taskContext.getString("evalId") != null){
            return taskContext.getString("evalId");
        }
        return id.toString();
    }

    public OdoClient(UUID clientId){
        this(clientId, null);
    }

    public OdoClient(UUID clientId, JsonObject taskContext){
        this.id = clientId;
        this.taskContext = taskContext;

        this.controlConnectionManager = new ControlConnectionManager(this);
        this.requestManager = new RequestManager(this); //Request Manager must be initalized after control connection manager
        this.eventConnectionManager = new EventConnectionManager(this); //Event connection manager must be called after RequestManager is bound to the request.
        this.guidanceConnectionManager = new GuidanceConnectionManager(this);

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
