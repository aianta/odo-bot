package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class EventConnectionManager extends AbstractConnectionManager implements ConnectionManager{

    Map<String, Promise> activePromises = new LinkedHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(EventConnectionManager.class);
    private static final String SOURCE = "EventConnectionManager";

    private OnlineEventProcessor eventProcessor = new OnlineEventProcessor();

    public OnlineEventProcessor getEventProcessor(){
        return eventProcessor;
    }

    public EventConnectionManager(OdoClient client){
        super(client);
        eventProcessor.setOnEntity(entity -> log.info("online timeline got: {}", entity.symbol()));
        eventProcessor.setOnEntity(client.getRequestManager()::instructionWatcher, entity -> entity instanceof DataEntry || entity instanceof ClickEvent || entity instanceof CheckboxEvent || entity instanceof NetworkEvent || entity instanceof ApplicationLocationChange);
        eventProcessor.setOnEntity(client.getRequestManager()::pathCompletionWatcher, entity -> entity instanceof NetworkEvent);
    }



    public void onMessage(JsonObject message){
        log.info("EventConnectionManager got {}", message.getString("type"));
        switch (message.getString("type")){
            case "LOCAL_CONTEXT":
                Promise promise = activePromises.get("LOCAL_CONTEXT");
                promise.complete(message);
                try(FileOutputStream fos = new FileOutputStream(new File("local-context.json"));
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                ){
                    bos.write(message.encodePrettily().getBytes(StandardCharsets.UTF_8));

                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                activePromises.remove("LOCAL_CONTEXT");
                break;
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
            case "EVENT":

                JsonObject event = message.getJsonObject("event");
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

    public Future<JsonArray> getLocalContext(){

        JsonObject localContextRequest = new JsonObject()
                .put("type", "GET_LOCAL_CONTEXT")
                .put("source", "EventConnectionManager");

        if(client.getRequestManager().getActiveRequest() != null){
            localContextRequest.put("pathsRequestId", client.getRequestManager().getActiveRequest().id().toString());
        }

        if(client.getRequestManager().getActiveExecutionRequest() != null){
            localContextRequest.put("pathsRequestId", client.getRequestManager().getActiveExecutionRequest().getId().toString());
        }

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("LOCAL_CONTEXT", promise);

        log.info("Sending local context request!");

        send(localContextRequest);

        return promise.future().compose( response-> Future.succeededFuture(response.getJsonArray("localContext")));
    }

    public Future<Void> startTransmitting(){

        JsonObject startTransmissionRequest = new JsonObject()
                .put("type", "START_TRANSMISSION")
                .put("source", "EventConnectionManager");

        if(client.getRequestManager().getActiveRequest() != null){
            startTransmissionRequest.put("pathsRequestId", client.getRequestManager().getActiveRequest().id().toString());
        }

        if(client.getRequestManager().getActiveExecutionRequest() != null){
            startTransmissionRequest.put("pathsRequestId", client.getRequestManager().getActiveExecutionRequest().getId().toString());
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
                .put("pathsRequestId", client.getRequestManager().getActiveRequest().id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("TRANSMISSION_STOPPED", promise);

        log.info("Requesting transmission of user events to end!");

        send(stopTransmissionRequest);

        return promise.future().compose(response->Future.succeededFuture());
    }
}
