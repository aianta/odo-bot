package ca.ualberta.odobot.guidance.llm.uicontrol.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.guidance.llm.uicontrol.AIStrategy;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy {

    public OpenAIStrategy(JsonObject config) {
        super(config);
    }

    @Override
    public Future<JsonArray> computeTargetUIControlState(String taskDescription, JsonObject uiControlInfo, JsonArray currentUIControlState) {
        return null;
    }
}
