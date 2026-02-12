package ca.ualberta.odobot.common;

import io.vertx.core.json.JsonObject;

import java.util.function.Predicate;

public class Predicates {

    public static Predicate<JsonObject> stateClusteringEventFilter(){
        return (event)->
                event.containsKey("eventDetails_domSnapshot") &&
                        event.getString("eventDetails_name") != null &&
                        event.containsKey("eventDetails_name") &&
                        !event.getString("eventDetails_name").equals("NETWORK_EVENT") &&
                        !event.getString("eventDetails_name").equals("DOM_EFFECT");

    }
}
