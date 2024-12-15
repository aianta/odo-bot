package ca.ualberta.odobot.guidance.connectionmanagers;

import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.Request;
import ca.ualberta.odobot.guidance.WebSocketConnection;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.execution.SchemaParameter;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.snippet2xml.SemanticObject;
import ca.ualberta.odobot.snippet2xml.Snippet2XMLVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class GuidanceConnectionManager extends AbstractConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(GuidanceConnectionManager.class);
    private static final String SOURCE = "GuidanceConnectionManager";
    private Map<String, Promise> activePromises = new LinkedHashMap<>();



    public GuidanceConnectionManager(OdoClient client){
        super(client);
    }

    public void onMessage(JsonObject message){
        switch (message.getString("type")){
            case "NAVIGATION_OPTIONS_SHOW_RESULT":
                Promise promise = activePromises.get("NAVIGATION_OPTIONS_SHOW_RESULT");
                promise.complete(message);
                activePromises.remove("NAVIGATION_OPTIONS_SHOW_RESULT");
                break;
            case "PATH_COMPLETE_ACK":
                activePromises.get("PATH_COMPLETE_ACK").complete(message);
                activePromises.remove("PATH_COMPLETE_ACK");
                break;
            case  "EXECUTION_RESULT":
                activePromises.get("EXECUTION_RESULT").complete(message);
                activePromises.remove("EXECUTION_RESULT");

                break;
        }
    }

    public Future<JsonObject> clearNavigationOptions(){
        JsonObject clearNavigationOptionsRequest = new JsonObject()
                .put("type", "CLEAR_NAVIGATION_OPTIONS")
                .put("source", SOURCE)
                .put("pathsRequestId", client.getRequestManager().getActiveRequest().id().toString());

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("CLEAR_NAVIGATION_OPTIONS_RESULT", promise);

        send(clearNavigationOptionsRequest);

        return promise.future();
    }

    public Future<JsonObject> notifyPathComplete(){
        JsonObject notifyPathCompleteRequest = makeNotifyPathCompleteRequest(SOURCE);
        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("PATH_COMPLETE_ACK", promise);

        send(notifyPathCompleteRequest);
        return promise.future();
    }

    public Future<JsonObject> showNavigationOptions(JsonObject navigationOptions){
        JsonObject showNavigationOptionsRequest = new JsonObject()
                .put("type", "SHOW_NAVIGATION_OPTIONS")
                .put("source", SOURCE)
                .put("pathsRequestId", client.getRequestManager().getActiveRequest().id().toString())
                .mergeIn(navigationOptions);

        Promise<JsonObject> promise = Promise.promise();
        activePromises.put("NAVIGATION_OPTIONS_SHOW_RESULT", promise);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        send(showNavigationOptionsRequest);

        return promise.future();
    }

    public Future<JsonObject> sendExecutionInstruction(JsonObject instruction){
        JsonObject executionRequest = new JsonObject()
                .put("type", "EXECUTE")
                .put("source", SOURCE)
                .put("executionId", client.getRequestManager().getActiveExecutionRequest().getId().toString())
                .mergeIn(instruction);

        Promise<JsonObject> promise = Promise.promise();

        if(executionRequest.getString("action").equals("queryDom")){
            //queryDom requests will respond with a bunch of HTML elements, from which we need to pick one to convert to a click action.

            promise.future().onSuccess(response->{

                List<JsonObject> queryResults = response.getJsonArray("queryResults").stream().map(o->(JsonObject)o).collect(Collectors.toList());
                log.info("Last result: \n{}", queryResults.get(queryResults.size()-1).getString("html"));

                String schemaId = LogPreprocessor.neo4j.getSchemaId(executionRequest.getString("parameterId"));

                Snippet2XMLVerticle.sqliteService.getSemanticSchemaById(schemaId)
                        .compose(schema -> {
                           return Future.join(
                                   queryResults.stream()
                                           .map(html->Snippet2XMLVerticle.snippet2XML.getObjectFromHTMLIgnoreSchemaIssues(html.getString("html"), schema).compose(semanticObject -> {
                                               return Future.succeededFuture(new JsonObject().put("semanticObject", semanticObject.toJson()).put("xpath", html.getString("xpath")));
                                           }, err->Future.succeededFuture(null)))
                                           .collect(Collectors.toList())
                           );
                        })
                        .compose(compositeFuture -> {
                            List<JsonObject> objects = compositeFuture.list().stream()
                                    .filter(Objects::nonNull) //It's possible some html will fail to resolve to proper xml objects.
                                    .map(o->(JsonObject)o).collect(Collectors.toList());

                            Map<String, String> objectMap = new HashMap<>();
                            objects.forEach(object->{
                                SemanticObject semanticObject = new SemanticObject(object.getJsonObject("semanticObject"));
                                objectMap.put(semanticObject.getObject(), object.getString("xpath"));
                            });

                            List<SemanticObject> options = objects.stream().map(json->new SemanticObject(json.getJsonObject("semanticObject"))).collect(Collectors.toList());

                            ExecutionRequest request = client.getRequestManager().getActiveExecutionRequest();

                            return Snippet2XMLVerticle.snippet2XML.pickParameterValue(options, ((SchemaParameter)request.getParameter(executionRequest.getString("parameterId"))).getQuery())
                                    //Resolve the picked semantic object to its corresponding xpath...
                                    .compose(semanticObject -> Future.succeededFuture(objectMap.get(semanticObject.getObject())))
                                    ;

                        })
                        .onSuccess(option->{
                            log.info("Picked option: {}", option);
                            JsonObject clickRequest = new JsonObject()
                                    .put("type", "EXECUTE")
                                    .put("source", SOURCE)
                                    .put("executionId", client.getRequestManager().getActiveExecutionRequest().getId().toString())
                                    .put("action", "click")
                                    .put("xpath", option);

                            activePromises.put("EXECUTION_RESULT", Promise.promise());
                            try{
                                Thread.sleep(1000);
                            }catch (InterruptedException e){
                                throw new RuntimeException(e);
                            }
                            send(clickRequest);
                        })
                        .onFailure(err->{
                            log.error("Error while handling queryDom execution result!");
                            log.error(err.getMessage(), err);
                        })
                ;

            });


        }

        activePromises.put("EXECUTION_RESULT", promise);
        try{
            Thread.sleep(3000);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }
        send(executionRequest);

        return promise.future();
    }
}
