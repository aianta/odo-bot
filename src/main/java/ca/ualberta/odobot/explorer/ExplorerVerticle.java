package ca.ualberta.odobot.explorer;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * @author Alexandru Ianta
 * A wrapper verticle for the Odo Explorer, a selenium based tool which
 * explores a web application while running the Odo Sight extension in
 * order to generate data that can be used to train TPG.
 */
public class ExplorerVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ExplorerVerticle.class);

    private static final String HOST = "0.0.0.0";

    private static final int PORT = 8076;

    private static final String API_PATH_PREFIX = "/api/*";

    public String serviceName(){return "Data Generation (Explorer) Service";}

    public String configFilePath(){
        return "config/explorer.yaml";
    }


    public Completable onStart(){
        super.onStart();
        try{

            api.route(HttpMethod.POST, "/explore").handler(this::exploreValidationHandler);
            api.route(HttpMethod.POST, "/explore").handler(this::exploreHandler);
            api.route(HttpMethod.POST, "/plan").handler(this::planValidationHandler);
            api.route(HttpMethod.POST, "/plan").handler(this::planHandler);
            api.route(HttpMethod.POST, "/evaluationTasks").handler(this::evaluationTasksHandler);


        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return Completable.complete();

    }

    private void exploreValidationHandler(RoutingContext rc){
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();

            validateFields(rc, config, ExploreRequestFields.values());

            //If routing context wasn't ended during validation, let's proceed.
            if(!rc.response().ended()){
                rc.put("exploreConfig", config);
                rc.next();
            }

        }else{
            rc.response().setStatusCode(400).end("Explore request did not contain expected ExploreTask configuration in JSON format in the body of the request.");
        }



    }

    private void exploreHandler(RoutingContext rc){
        JsonObject config = rc.get("exploreConfig");

        ExploreTask exploreTask = new ExploreTask(config);
        Thread thread = new Thread(exploreTask);
        thread.start();

        rc.response().setStatusCode(200).end("Exploring started!");


    }

    private void planValidationHandler(RoutingContext rc){
        //TODO - any validation logic that makes sense
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();

            validateFields(rc, config, PlanRequestFields.values());

            if(!rc.response().ended()){
                rc.put("planConfig", config);
                rc.next();
                return;
            }
        }
        rc.response().setStatusCode(400).end("Explore request did not contain expected PlanTask configuration in JSON format in the body of the request.");

    }

    private void planHandler(RoutingContext rc){

        JsonObject planConfig = rc.get("planConfig");

        Promise<JsonObject> promise = Promise.promise();
        promise.future()
                .onFailure(err->serverError(rc, err))
                .onSuccess(plan->rc.response().setStatusCode(200).end(plan.encode()));

        PlanTask planTask = new PlanTask(planConfig, promise);
        Thread thread = new Thread(planTask);
        thread.start();
    }

    private void evaluationTasksHandler(RoutingContext rc){
        Promise<JsonArray> promise = Promise.promise();
        promise.future()
                .onFailure(err->serverError(rc, err))
                .onSuccess(results->rc.response().setStatusCode(200).end(results.encodePrettily()));

        EvaluationTaskGenerationTask task = new EvaluationTaskGenerationTask(rc.body().asJsonObject(), promise);
        Thread thread = new Thread(task);
        thread.start();
    }

    private void serverError(RoutingContext rc, Throwable err){
        log.error(err.getMessage(), err);
        rc.response().setStatusCode(500).end(err.getMessage());
    }

    private void badRequest(RoutingContext rc, String message){
        rc.response().setStatusCode(400).end(message);
    }

    private <T extends RequestFields> void validateFields(RoutingContext rc, JsonObject config, T[] fieldsEnum){

        Arrays.stream(fieldsEnum).forEach(
                key->{
                    //Check that the fields exist
                    if(!config.containsKey(key.field())){
                        badRequest(rc, "Explore request body missing " + key.field() + " key.");
                    }

                    //Check that the string fields aren't blank
                    if(config.containsKey(key.field()) && key.type().equals(String.class) && config.getString(key.field()).isBlank()){
                        badRequest(rc, "Explore request body field "+key.field()+" cannot be empty." );
                    }
                }
        );


    }

}
