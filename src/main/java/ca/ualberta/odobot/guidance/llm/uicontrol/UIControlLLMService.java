package ca.ualberta.odobot.guidance.llm.uicontrol;

import ca.ualberta.odobot.guidance.llm.uicontrol.impl.UIControlLLMServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ProxyGen
public interface UIControlLLMService {

    static UIControlLLMService create(Vertx vertx, JsonObject config, Strategy strategy){
        return new UIControlLLMServiceImpl(vertx, config, strategy);
    }

    static UIControlLLMService createProxy(Vertx vertx, String address){
        return new UIControlLLMServiceVertxEBProxy(vertx, address);
    }

    /**
     * This method is called to identify what the target state of a UI control (text input, checkbox, radio button group, etc)
     * should be in the context of the provided task. IE: Should a checkbox be checked? Which radio button should be selected?
     * What text should be entered into the text field?
     *
     * @param taskDescription the natural language description of the task.
     * @param uiControlInfo Information about the UI control sourced from the trajectories/traces and the nav model and other apriori sources.
     * @param currentUIControlState  Information about the current state of the UI control retrieved from the application.
     * @return a JsonArray containing one or more JsonObjects describing the target state of the UI control.
     */
    Future<JsonArray> computeTargetUIControlState(String taskDescription, JsonObject uiControlInfo, JsonArray currentUIControlState );


}
