package ca.ualberta.odobot.semanticflow;

import co.elastic.clients.json.JsonData;
import io.vertx.core.json.JsonObject;

/**
 * Convert JsonData to Vert.x JsonObjects
 */
public class JsonDataUtility {

    public static JsonObject fromJsonData(JsonData data){
        JsonObject result = new JsonObject(data.toString());
        return result;
    }

}
