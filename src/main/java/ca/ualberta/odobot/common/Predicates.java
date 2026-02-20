package ca.ualberta.odobot.common;

import ca.ualberta.odobot.semanticflow.model.InteractionType;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

public class Predicates {
    private static final Logger log = LoggerFactory.getLogger(Predicates.class);


    public static Predicate<JsonObject> stateClusteringEventFilter(){
        return (event)->
                event.containsKey("eventDetails_domSnapshot") &&
                        event.getString("eventDetails_name") != null &&
                        event.containsKey("eventDetails_name") &&
                        !event.getString("eventDetails_name").equals("NETWORK_EVENT") &&
                        !event.getString("eventDetails_name").equals("DOM_EFFECT");

    }

    public static Predicate<JsonObject> annotationEventFilter(){
        return (event)->{
//            if(event.getString("eventDetails_name") == null){
//                log.warn("eventDetails_name is null");
//                log.info("{}", event.encodePrettily());
//            }

            return event.containsKey("eventDetails_name") && InteractionType.getType(event.getString("eventDetails_name")) == InteractionType.CLICK;
        };
    }
}
