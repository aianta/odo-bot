package ca.ualberta.odobot.dataentry2label;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface AIStrategy {

    Future<JsonObject> generateLabelAndDescription(JsonObject input);

}
