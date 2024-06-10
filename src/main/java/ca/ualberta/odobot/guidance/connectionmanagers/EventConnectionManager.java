package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class EventConnectionManager implements ConnectionManager{

    Map<String, Promise> activePromises = new LinkedHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(EventConnectionManager.class);

    private Request request;

    private WebSocketConnection connection = null;


    public EventConnectionManager(Request request){
        this.request = request;
    }

    @Override
    public void updateConnection(WebSocketConnection connection) {
        this.connection = connection;
        this.connection.setMessageConsumer(this::onMessage);
    }

    public void onMessage(JsonObject message){
        switch (message.getString("type")){
            case "LOCAL_CONTEXT":
                Promise promise = activePromises.get("LOCAL_CONTEXT");
                promise.complete(message);
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
            case "EVENT":
               if(message.encode().contains("NETWORK_EVENT")){
                   log.info("{}", message.encodePrettily());
               }


                break;
        }
    }

    public Future<JsonArray> getLocalContext(){

        JsonObject localContextRequest = new JsonObject()
                .put("type", "GET_LOCAL_CONTEXT")
                .put("source", "EventConnectionManager")
                .put("pathsRequestId", request.id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("LOCAL_CONTEXT", promise);

        log.info("Sending local context request!");

        connection.send(localContextRequest);

        return promise.future().compose( response-> Future.succeededFuture(response.getJsonArray("localContext")));
    }

    public Future<Void> startTransmitting(){

        JsonObject startTransmissionRequest = new JsonObject()
                .put("type", "START_TRANSMISSION")
                .put("source", "EventConnectionManager")
                .put("pathsRequestId", request.id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("TRANSMISSION_STARTED", promise);

        log.info("Requesting transmission of user events!");

        connection.send(startTransmissionRequest);

        return promise.future().compose(response->Future.succeededFuture());
    }

    public Future<Void> stopTransmitting(){

        JsonObject stopTransmissionRequest = new JsonObject()
                .put("type", "STOP_TRANSMISSION")
                .put("source", "EventConnectionManager")
                .put("pathsRequestId", request.id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("TRANSMISSION_STOPPED", promise);

        log.info("Requesting transmission of user events to end!");

        connection.send(stopTransmissionRequest);

        return promise.future().compose(response->Future.succeededFuture());
    }
}
