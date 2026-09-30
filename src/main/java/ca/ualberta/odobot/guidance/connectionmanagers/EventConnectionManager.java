package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.GuidanceVerticle;
import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.UnchartedStepObserver;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class EventConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    Map<String, Promise> activePromises = new LinkedHashMap<>();

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
        super(client);

        eventProcessor.setOnEntity(entity -> log.info("online timeline got: {}", entity.symbol()));
        eventProcessor.setOnEntity(client.getRequestManager()::onObservation);
        eventProcessor.setOnUnchartedObservation(client.getRequestManager()::onUnchartedObservation);

        stepObserver = new UnchartedStepObserver(GuidanceVerticle._vertx, this::requestScreenshot, eventProcessor::addUnchartedObservation);
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
        send(new JsonObject()
                .put("type", "GET_SCREENSHOT")
                .put("source", SOURCE));
    }



    public void onMessage(JsonObject message){
        //log.info("EventConnectionManager got {}", message.getString("type"));
        if(eventContext == null){
            eventContext = Vertx.currentContext();
        }
        switch (message.getString("type")){
            case "TRANSMISSION_STARTED":
                activePromises.get("TRANSMISSION_STARTED").complete(message);
                activePromises.remove("TRANSMISSION_STARTED");
                break;
            case "TRANSMISSION_STOPPED":
                activePromises.get("TRANSMISSION_STOPPED").complete(message);
                activePromises.remove("TRANSMISSION_STOPPED");
                break;
            case "PATH_COMPLETE_ACK":
                //TODO -> What, if anything, should the server do here?
                activePromises.get("PATH_COMPLETE_ACK").complete(message);
                activePromises.remove("PATH_COMPLETE_ACK");
                break;
            case "SCREENSHOT":
                stepObserver.onScreenshot(message.getString("screenshot"), message.getString("userLocation"));
                break;
            case "EVENT":

                JsonObject event = message.getJsonObject("event");
                stepObserver.onEvent();
                eventProcessor.process(event);


                break;
        }
    }

    public Future<JsonObject> notifyPathComplete(){
        JsonObject notifyPathCompleteRequest = makeNotifyPathCompleteRequest(SOURCE);
        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("PATH_COMPLETE_ACK", promise);
        send(notifyPathCompleteRequest);
        return promise.future();
    }

    public Future<Void> startTransmitting(){

        JsonObject startTransmissionRequest = new JsonObject()
                .put("type", "START_TRANSMISSION")
                .put("source", "EventConnectionManager");


        if(client.getRequestManager().getExecutionId() != null){
            startTransmissionRequest.put("pathsRequestId", client.getRequestManager().getExecutionId().toString());
        }


        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("TRANSMISSION_STARTED", promise);

        log.info("Requesting transmission of user events!");

        send(startTransmissionRequest);

        return promise.future().compose(response->{
            log.info("Confirmed transmission started!");
            return Future.succeededFuture();
        });
    }

    public Future<Void> stopTransmitting(){

        JsonObject stopTransmissionRequest = new JsonObject()
                .put("type", "STOP_TRANSMISSION")
                .put("source", "EventConnectionManager")
                .put("pathsRequestId", client.getRequestManager().getExecutionId().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("TRANSMISSION_STOPPED", promise);

        log.info("Requesting transmission of user events to end!");

        send(stopTransmissionRequest);

        return promise.future().compose(response->Future.succeededFuture());
    }
}
