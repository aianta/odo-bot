package ca.ualberta.odobot.web;


import ca.ualberta.odobot.semanticflow.SemanticFlowParser;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.core.http.HttpServerResponse;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.*;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TimelineWebApp extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimelineWebApp.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8080;

    HttpServer server;
    Router mainRouter;
    Router api;

    Map<String, JsonObject> timelines = new LinkedHashMap<>();
    Map<String, JsonObject> annotations = new LinkedHashMap<>();

    private static TimelineWebApp instance;

    public static TimelineWebApp getInstance(){
        if(instance == null){
            instance = new TimelineWebApp();
        }
        return instance;
    }

    @Override
    public Completable rxStart() {


        try{
            log.info("Starting Timeline Web App");
            log.info("Loading timelines and annotations...");
            loadTimelinesAndAnnotations();

            HttpServerOptions options = new HttpServerOptions()
                    .setHost(HOST)
                    .setPort(PORT)
                    .setSsl(false);

            server = vertx.createHttpServer(options);
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            //Define API routes
            api.route().method(HttpMethod.GET).path("/timelines/").handler(this::getTimelines);
            api.route().method(HttpMethod.GET).path("/annotations/:timelineId/").handler(this::getAnnotation);
            api.route().method(HttpMethod.PUT).path("/annotations/:timelineId/").handler(this::updateAnnotation);


            //Mount API routes
            mainRouter.route().handler(LoggerHandler.create());
            mainRouter.route().handler(BodyHandler.create());
            mainRouter.route().handler(rc->{
                rc.response().putHeader("Access-Control-Allow-Origin","*");
                rc.next();
            });
            mainRouter.route().handler(FaviconHandler.create(vertx));
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

        if(annotation.isPresent()) rc.response().putHeader("Content-Type", "application/json").end(annotation.get().encode());
        else rc.fail(404);

    }

    void updateAnnotation(RoutingContext rc){
        UUID timelineId = UUID.fromString(rc.pathParam("timelineId"));

        JsonObject payload = rc.body().asJsonObject();
        log.info("Updated annotation: {}", payload.encodePrettily());
        //Ensure annotation contains the correct id
        if(payload.containsKey("id") && payload.getString("id").equals(timelineId.toString())){
            try{
                String annotationPath = getAnnotationPath(timelineId);
                //Delete old annotation file
                Files.deleteIfExists(Path.of(annotationPath));

                //Write the new annotation file
                File updatedAnnotation = new File(annotationPath);
                try(
                        FileWriter fw  = new FileWriter(updatedAnnotation);
                        BufferedWriter bw = new BufferedWriter(fw);
                        ){
                    bw.write(payload.encode());
                    bw.flush();
                }

                //Update the runtime map of annotations
                annotations.put(annotationPath, payload);

                rc.response().setStatusCode(200).end();

            }catch (IOException e){
                log.error(e.getMessage(), e);
            }


        }else{
            //Bad request
            rc.fail(400);
        }

    }

    /**
     * Return a list of timelines available on the server.
     * @param rc
     */
    void getTimelines(RoutingContext rc){
        rc.response().putHeader("Content-Type", "application/json")
                .end(
                        timelines.entrySet().stream()
                                //Impose chronological order
                                .sorted( new Comparator<Map.Entry<String, JsonObject>>() {
                                        @Override
                                        public int compare(Map.Entry<String, JsonObject> o1, Map.Entry<String, JsonObject> o2) {
                                            File f1 = new File(o1.getKey());
                                            File f2 = new File(o2.getKey());
                                            return (int)(f1.lastModified() - f2.lastModified());
                                        }
                                    }
                                )
                                .map(entry->entry.getValue())
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily());
    }

    void notFoundHandler(RoutingContext rc){
        rc.response().end(new JsonObject().put("error", "An error has occured!").encode());
    }

    private String getAnnotationPath(UUID timelineId){
        return annotations.keySet().stream().filter(path->path.contains(timelineId.toString())).findFirst().get();
    }

    public void loadTimelinesAndAnnotations(){
        timelines.clear();
        annotations.clear();

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