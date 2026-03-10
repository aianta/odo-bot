package ca.ualberta.odobot.dataentry2label;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {

    Future<JsonObject> generateLabelAndDescription(JsonObject input);

    Future<JsonArray> standardizeLabels(List<JsonObject> labels);

}
