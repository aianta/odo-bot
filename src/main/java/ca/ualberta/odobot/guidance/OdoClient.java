package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.connectionmanagers.EventConnectionManager;
import ca.ualberta.odobot.guidance.connectionmanagers.GuidanceConnectionManager;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class OdoClient {

    private static final Logger log = LoggerFactory.getLogger(OdoClient.class);

    private UUID id;

    /**
     * The Vert.x instance the client's sockets were accepted on. The managers set their timers with it.
     */
    private final Vertx vertx;

    /**
     * The task this client was created for, see {@link TaskSockets}. Null if no task expects it.
     */
    private JsonObject taskContext;

    //Request Manager
    private RequestManager requestManager = null;

    //Connection Managers
    private EventConnectionManager eventConnectionManager = null;
    private GuidanceConnectionManager guidanceConnectionManager = null;

    //OdoX's socket, a placeholder until OdoX connects.
    private WebSocketConnection socket = new WebSocketConnection();

    /**
     * Whether OdoX has a page ready for guidance messages, see {@link #setPageReady}. The guidance channel is open only
     * while it does.
     */
    private volatile boolean pageReady = false;

    public JsonObject statusReport(){
        JsonObject report = new JsonObject()
                .put("id", id().toString())
                .put("task", taskContext)
                .put("socket", socket.isConnected()?"connected":"disconnected")
                .put("page", pageReady?"ready":"not ready");

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

    public OdoClient(UUID clientId, Vertx vertx){
        this(clientId, null, vertx);
    }

    public OdoClient(UUID clientId, JsonObject taskContext, Vertx vertx){
        this.id = clientId;
        this.taskContext = taskContext;
        this.vertx = vertx;

        this.requestManager = new RequestManager(this);
        this.eventConnectionManager = new EventConnectionManager(this); //Event connection manager must be called after RequestManager is bound to the request.
        this.guidanceConnectionManager = new GuidanceConnectionManager(this);

    }

    public Vertx vertx(){
        return vertx;
    }

    public WebSocketConnection getSocket() {
        return socket;
    }

    /**
     * Use a newly connected socket of OdoX, e.g. after OdoX reconnected. The page OdoX had ready before is forgotten;
     * OdoX reports it again on the new socket.
     */
    public void setSocket(WebSocketConnection socket) {
        this.socket = socket;
        this.pageReady = false;
        socket.setMessageConsumer(this::route);

        eventConnectionManager.updateConnection(new ChannelConnection(Channel.EVENT, socket, ()->true));
        guidanceConnectionManager.updateConnection(new ChannelConnection(Channel.GUIDANCE, socket, this::isPageReady));
    }

    /**
     * Hand a message from OdoX to the manager of its channel.
     */
    public void route(JsonObject message){
        Channel channel = Channel.fromName(message.getString("channel"));
        if(channel == null){
            log.warn("[{}] Dropping a message of type {} on unknown channel {}", label(), message.getString("type"), message.getString("channel"));
            return;
        }

        switch (channel){
            case EVENT -> eventConnectionManager.dispatch(message);
            case GUIDANCE -> guidanceConnectionManager.dispatch(message);
        }
    }

    public boolean isPageReady(){
        return pageReady;
    }

    /**
     * OdoX reported that a page is ready for guidance messages (PAGE_READY), or that it is gone (PAGE_GONE), e.g. while
     * the page navigates. Guidance messages wait while there is no page, and go out in order once one is ready.
     */
    public void setPageReady(boolean ready){
        log.info("[{}] Page {}", label(), ready? "ready" : "gone");
        this.pageReady = ready;
        if(ready){
            guidanceConnectionManager.flushQueue();
        }
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
