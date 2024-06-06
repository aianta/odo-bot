package ca.ualberta.odobot.guidance;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestManager {

    private static final Logger log = LoggerFactory.getLogger(RequestManager.class);

    private Request request = null;

    public RequestManager(Request request){
        this.request = request;
        this.request.getControlConnectionManager().setNewRequestTargetNodeConsumer(this::handleNewPathsRequest);
    }

    public void handleNewPathsRequest(String targetNode){

        request.getEventConnectionManager().getLocalContext()
                .compose(localContext->getPaths(targetNode, localContext))
                .compose(navigationOptions->request.getGuidanceConnectionManager().showNavigationOptions(navigationOptions))
                .onSuccess(navigationOptionsShown->request.getEventConnectionManager().startTransmitting());
        ;


    }

    public Future<JsonObject> getPaths(String targetNode, JsonArray localContext){
        return Future.succeededFuture(new JsonObject()
                .put("navigationOptions", new JsonArray()
                        .add(new JsonObject().put("xpath", "//html/body/div[3]/div[2]/div/div/div[1]/div/div/div/div/div/div[2]/form[1]/div[3]/div[2]/button"))
                ));
    }



}
