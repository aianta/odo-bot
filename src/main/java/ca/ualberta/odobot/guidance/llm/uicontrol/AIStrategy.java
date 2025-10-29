package ca.ualberta.odobot.guidance.llm.uicontrol;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


public interface AIStrategy {

    Future<JsonArray> computeTargetUIControlState(String taskDescription, JsonObject uiControlInfo, JsonArray currentUIControlState);
}
