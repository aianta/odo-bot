package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Channel;
import ca.ualberta.odobot.guidance.ChannelConnection;
import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.UnchartedStepObserver;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event channel: OdoX's events, transmission control and screenshots, handled by OdoX's background.
 */
public class EventConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    private static final Logger log = LoggerFactory.getLogger(EventConnectionManager.class);
    private static final String SOURCE = "EventConnectionManager";

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    private final UnchartedStepObserver stepObserver;

    /**
     * The context OdoX's messages arrive on. The step observer runs there.
     */
    private Context eventContext = null;

    public OnlineEventProcessor getEventProcessor(){
        return eventProcessor;
    }

    public EventConnectionManager(OdoClient client){
        super(client, Channel.EVENT);

        eventProcessor.setOnEntity(entity -> log.info("online timeline got: {}", entity.symbol()));
        eventProcessor.setOnEntity(client.getRequestManager()::onObservation);
        eventProcessor.setOnUnchartedObservation(client.getRequestManager()::onUnchartedObservation);

        stepObserver = new UnchartedStepObserver(client.vertx(), this::requestScreenshot, eventProcessor::addUnchartedObservation);
    }

    @Override
    public void updateConnection(ChannelConnection connection) {
        super.updateConnection(connection);
        eventContext = Vertx.currentContext();
    }

    /**
     * Observes an uncharted step that has just been sent to OdoX, see {@link UnchartedStepObserver}.
     * @param step the step instruction as sent.
     */
    public void observeUnchartedStep(JsonObject step){
        onEventContext(()->stepObserver.observe(step));
    }

    public void stopObservingUnchartedSteps(){
        onEventContext(stepObserver::stop);
    }

    private void onEventContext(Runnable action){
        if(eventContext != null && Vertx.currentContext() != eventContext){
            eventContext.runOnContext(v->action.run());
        }else{
            action.run();
        }
    }

    private void requestScreenshot(){
        request(new JsonObject()
                .put("type", "GET_SCREENSHOT")
                .put("source", SOURCE))
                .onSuccess(reply->stepObserver.onScreenshot(reply.getString("screenshot"), reply.getString("userLocation")));
    }



    public void onMessage(JsonObject message){
        if(eventContext == null){
            eventContext = Vertx.currentContext();
        }
        switch (message.getString("type")){
            case "EVENT":

                JsonObject event = message.getJsonObject("event");
                stepObserver.onEvent();
                eventProcessor.process(event);


                break;
            default:
                log.warn("Unexpected message of type {} on the event channel.", message.getString("type"));
        }
    }

    public Future<JsonObject> notifyPathComplete(){
        return request(makeNotifyPathCompleteRequest(SOURCE));
    }

    public Future<Void> startTransmitting(){

        JsonObject startTransmissionRequest = new JsonObject()
                .put("type", "START_TRANSMISSION")
                .put("source", "EventConnectionManager");


        if(client.getRequestManager().getExecutionId() != null){
            startTransmissionRequest.put("pathsRequestId", client.getRequestManager().getExecutionId().toString());
        }

        log.info("Requesting transmission of user events!");

        return request(startTransmissionRequest).compose(response->{
            log.info("Confirmed transmission started!");
            return Future.succeededFuture();
        });
    }

    public Future<Void> stopTransmitting(){

        JsonObject stopTransmissionRequest = new JsonObject()
                .put("type", "STOP_TRANSMISSION")
                .put("source", "EventConnectionManager")
                .put("pathsRequestId", client.getRequestManager().getExecutionId().toString());

        log.info("Requesting transmission of user events to end!");

        return request(stopTransmissionRequest).compose(response->Future.succeededFuture());
    }
}
