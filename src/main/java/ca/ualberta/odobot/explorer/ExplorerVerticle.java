package ca.ualberta.odobot.explorer;

import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
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
public class ExplorerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ExplorerVerticle.class);

    private static final String HOST = "0.0.0.0";

    private static final int PORT = 8076;

    private static final String API_PATH_PREFIX = "/api/*";

    HttpServer server;

    Router mainRouter;

    Router api;

    public Completable rxStart(){

        try{

            //Setup http server
            HttpServerOptions options = new HttpServerOptions()
                    .setHost(HOST)
                    .setPort(PORT)
                    .setSsl(false);

            server = vertx.createHttpServer(options);
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            api.route(HttpMethod.POST, "/explore").handler(this::exploreValidationHandler);
            api.route(HttpMethod.POST, "/explore").handler(this::exploreHandler);

            mainRouter.route().handler(LoggerHandler.create());
            mainRouter.route().handler(BodyHandler.create());
            mainRouter.route().handler(rc->{
                rc.response().putHeader("Access-Control-Allow-Origin", "*");
                rc.next();
            });
            mainRouter.route(API_PATH_PREFIX).subRouter(api);

            server.requestHandler(mainRouter).listen(PORT);

            log.info("Explorer verticle started, API available on port {}", PORT);

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return super.rxStart();

    }

    private void exploreValidationHandler(RoutingContext rc){
        if(rc.body().available()){
            JsonObject config = rc.body().asJsonObject();

            validateFields(rc, config);

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

    private void badRequest(RoutingContext rc, String message){
        rc.response().setStatusCode(400).end(message);
    }

    private void validateFields(RoutingContext rc, JsonObject config){

        Arrays.stream(ExploreRequestFields.values()).forEach(
                key->{
                    //Check that the fields exist
                    if(!config.containsKey(key.field)){
                        badRequest(rc, "Explore request body missing " + key.field + " key.");
                    }

                    //Check that the string fields aren't blank
                    if(config.containsKey(key.field) && key.type.equals(String.class) && config.getString(key.field).isBlank()){
                        badRequest(rc, "Explore request body field "+key.field+" cannot be empty." );
                    }
                }
        );


    }

}
