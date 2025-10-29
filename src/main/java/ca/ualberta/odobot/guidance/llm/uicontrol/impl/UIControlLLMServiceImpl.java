package ca.ualberta.odobot.guidance.llm.uicontrol.impl;

import ca.ualberta.odobot.guidance.llm.uicontrol.AIStrategy;
import ca.ualberta.odobot.guidance.llm.uicontrol.Strategy;
import ca.ualberta.odobot.guidance.llm.uicontrol.UIControlLLMService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class UIControlLLMServiceImpl implements UIControlLLMService {

    private AIStrategy strategy;
    private Vertx vertx;

    public  UIControlLLMServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this.vertx = vertx;
        this.strategy = switch (strategy){
            case OPENAI ->  new OpenAIStrategy(config);
        };
    }

    @Override
    public Future<JsonArray> computeTargetUIControlState(String taskDescription, JsonObject uiControlInfo, JsonArray currentUIControlState) {


        return vertx.executeBlocking(blocking->{
            this.strategy.computeTargetUIControlState(
                    taskDescription,
                    uiControlInfo,
                    currentUIControlState
            ).onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }
}
