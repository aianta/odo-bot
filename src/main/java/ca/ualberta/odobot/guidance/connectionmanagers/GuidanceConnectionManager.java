package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class GuidanceConnectionManager extends AbstractConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(GuidanceConnectionManager.class);

    private Map<String, Promise> activePromises = new LinkedHashMap<>();

    private Request request;

    public GuidanceConnectionManager(Request request){
        this.request = request;
    }

    public void onMessage(JsonObject message){
        switch (message.getString("type")){
            case "NAVIGATION_OPTIONS_SHOW_RESULT":
                Promise promise = activePromises.get("NAVIGATION_OPTIONS_SHOW_RESULT");
                promise.complete(message);
                break;
        }
    }

    public Future<JsonObject> clearNavigationOptions(){
        JsonObject clearNavigationOptionsRequest = new JsonObject()
                .put("type", "CLEAR_NAVIGATION_OPTIONS")
                .put("source", "GuidanceConnectionManager")
                .put("pathsRequestId", request.id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("CLEAR_NAVIGATION_OPTIONS_RESULT", promise);

        send(clearNavigationOptionsRequest);

        return promise.future();
    }

    public Future<JsonObject> showNavigationOptions(JsonObject navigationOptions){
        JsonObject showNavigationOptionsRequest = new JsonObject()
                .put("type", "SHOW_NAVIGATION_OPTIONS")
                .put("source", "GuidanceConnectionManager")
                .put("pathsRequestId", request.id().toString())
                .mergeIn(navigationOptions);

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("NAVIGATION_OPTIONS_SHOW_RESULT", promise);

        send(showNavigationOptionsRequest);

        return promise.future();
    }
}
