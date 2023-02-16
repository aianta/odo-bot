package ca.ualberta.odobot.web;


import ca.ualberta.odobot.semanticflow.SemanticFlowParser;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.StaticHandler;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class TimelineWebApp extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimelineWebApp.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final int PORT = 8180;

    HttpServer server;
    Router mainRouter;
    Router api;

    Map<String, JsonObject> timelines = new LinkedHashMap<>();
    Map<String, JsonObject> annotations = new LinkedHashMap<>();

    @Override
    public Completable rxStart() {

        try{
            log.info("Starting Timeline Web App");
            log.info("Loading timelines and annotations...");
            loadTimelinesAndAnnotations();

            server = vertx.createHttpServer();
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            //Define API routes
            api.route().method(HttpMethod.GET).path("/timelines/").handler(this::getTimelines);
            api.route().method(HttpMethod.GET).path("/annotations/:timelineId/").handler(this::getAnnotation);


            //Mount API routes
            mainRouter.route(API_PATH_PREFIX).subRouter(api);

            //Mount static files handler
            mainRouter.route("/*").handler(StaticHandler.create());

            server.requestHandler(mainRouter).listen(PORT);

            log.info("Timeline Web App Server started on port {}", PORT);


        }catch (Exception e){
            log.error(e.getMessage(),e);
        }
        return super.rxStart();
    }

    void getAnnotation(RoutingContext rc){
        UUID timelineId = UUID.fromString(rc.pathParam("timelineId"));

        Optional<JsonObject> annotation = annotations.values()
                .stream()
                .filter(json->json.containsKey("id") && json.getString("id").equals(timelineId.toString()))
                .findFirst();

        if(annotation.isPresent()) rc.response().end(annotation.get().encode());
        else rc.fail(404);

    }

    /**
     * Return a list of timelines available on the server.
     * @param rc
     */
    void getTimelines(RoutingContext rc){
        rc.response().end(timelines.values().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily());
    }

    void notFoundHandler(RoutingContext rc){
        rc.response().end(new JsonObject().put("error", "An error has occured!").encode());
    }


    private void loadTimelinesAndAnnotations(){
        File timelinesDir = new File(SemanticFlowParser.TIMELINE_DATA_FOLDER);
        File [] timelinesArray = timelinesDir.listFiles();

        for(int i = 0; i < timelinesArray.length; i++){
            File timelineDir = timelinesArray[i];

            final String timelinePath = timelineDir.getAbsolutePath() + "/timeline.json";
            final String annoationPath = timelineDir.getAbsolutePath() + "/annotations.json";

            try(
                FileInputStream fisTimeline = new FileInputStream(timelinePath);
                FileInputStream fisAnnoataions = new FileInputStream(annoationPath)
            ){
                JsonObject timelineJson = new JsonObject(IOUtils.toString(fisTimeline, "UTF-8"));
                JsonObject annotationsJson = new JsonObject(IOUtils.toString(fisAnnoataions, "UTF-8"));

                timelines.put(timelinePath, timelineJson);
                annotations.put(annoationPath, annotationsJson);

            } catch (FileNotFoundException e) {
                log.error(e.getMessage(),e);
            } catch (IOException ioException) {
                log.error(ioException.getMessage(), ioException);
            }

        }
    }

}
