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

public class GuidanceConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(GuidanceConnectionManager.class);

    private Map<String, Promise> activePromises = new LinkedHashMap<>();

    private Request request;

    private WebSocketConnection connection = null;

    public GuidanceConnectionManager(Request request){
        this.request = request;
    }

    @Override
    public void updateConnection(WebSocketConnection connection) {
        this.connection = connection;
        this.connection.setMessageConsumer(this::onMessage);
    }

    public void onMessage(JsonObject message){
        switch (message.getString("type")){
            case "NAVIGATION_OPTIONS_SHOW_RESULT":
                Promise promise = activePromises.get("NAVIGATION_OPTIONS_SHOW_RESULT");
                promise.complete(message);
                break;
        }
    }

    public Future<JsonObject> showNavigationOptions(JsonObject navigationOptions){
        JsonObject showNavigationOptionsRequest = new JsonObject()
                .put("type", "SHOW_NAVIGATION_OPTIONS")
                .put("source", "GuidanceConnectionManager")
                .put("pathsRequestId", request.id().toString())
                .mergeIn(navigationOptions);

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("NAVIGATION_OPTIONS_SHOW_RESULT", promise);

        connection.send(showNavigationOptionsRequest);

        return promise.future();
    }
}
