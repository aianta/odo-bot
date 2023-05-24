package ca.ualberta.odobot.web;


import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.Future;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.Promise;

import io.vertx.rxjava3.core.http.HttpServer;


import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;

import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.handler.*;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.function.Predicate;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class TimelineWebApp extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimelineWebApp.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8080;


    private static final String DEEP_SERVICE_EMBEDDING_ENDPOINT = "/embeddings/";
    private static final String DEEP_SERVICE_DISTANCES_ENDPOINT = "/embeddings/distance";

    private ElasticsearchService elasticsearchService;

    HttpServer server;
    Router mainRouter;
    Router api;

    WebClient client;

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
        client = WebClient.create(vertx);

        try{
            log.info("Initializing Elasticsearch Service Proxy");
            elasticsearchService = ElasticsearchService.createProxy(vertx.getDelegate(), ELASTICSEARCH_SERVICE_ADDRESS);

            log.info("Starting Timeline Web App");


            HttpServerOptions options = new HttpServerOptions()
                    .setHost(HOST)
                    .setPort(PORT)
                    .setSsl(false);

            server = vertx.createHttpServer(options);
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            //Define API routes
            api.route().method(HttpMethod.GET).path("/pipelines").handler(this::getPipelines);
            api.route().method(HttpMethod.GET).path("/executions").handler(this::getExecutions);
            api.route().method(HttpMethod.GET).path("/pipelines/:pipelineId/timelines").handler(this::getTimelines);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/timelines").handler(this::getExecutionTimelines);
            api.route().failureHandler(rc->{
                JsonObject errObject = new JsonObject()
                        .put("error", rc.failure().getMessage());
                rc.response().setStatusCode(rc.statusCode()).end(errObject.encode());
            });

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


    void getExecutions(RoutingContext rc){
        elasticsearchService.fetchAll(EXECUTIONS_INDEX)
                .onSuccess(executions->simpleJsonListResponse(executions, rc))
                .onFailure(err->rc.fail(500,err));
    }

    void getExecutionTimelines(RoutingContext rc){
        UUID executionId = UUID.fromString(rc.pathParam("executionId"));

        elasticsearchService.fetchAll(EXECUTIONS_INDEX) //Fetch all executions
                .compose(executions->Future.succeededFuture(executions.stream().filter(e->e.getString("id").equals(executionId.toString())).findFirst().get())) //Filter out the execution for this request
                .onSuccess(
                execution->{
                    //Create a set of timeline ids involved in this execution
                    Set<String> timelineIds = execution.getJsonArray("timelineIds").stream().map(o->(String)o).collect(Collectors.toSet());

                    elasticsearchService.fetchAll(PIPELINES_INDEX) //Fetch all pipelines
                            .compose(pipelines->Future.succeededFuture(pipelines.stream()
                                    .filter(p->p.getString("id").equals(execution.getString("pipelineId"))).findFirst()
                                    .orElseThrow(
                                            ()->new RuntimeException("PipelineId: " + execution.getString("pipelineId") +  " for execution: " +  execution.getString("id") + " could not be found!")))) //Filter out all pipelines except the one for this execution
                            .compose(pipeline->elasticsearchService.fetchAll(pipeline.getString("timelineIndex"))) //Fetch all the timelines for this pipeline
                            .compose(timelines->Future.succeededFuture(timelines.stream().filter(t->timelineIds.contains(t.getString("id"))).collect(Collectors.toList()))) //Return only the timelines involved in this execution
                            .onSuccess(filteredTimelines->simpleJsonListResponse(filteredTimelines, rc))
                            .onFailure(err->{
                                log.error(err.getMessage(), err);
                                rc.fail(500, err);
                            })
                    ;

                }
        ).onFailure(err->{
            log.error(err.getMessage(), err);
            rc.fail(500, err);
                });
    }

    void getTimelines(RoutingContext rc){
        UUID pipelineId = UUID.fromString(rc.pathParam("pipelineId"));

        //TODO -> One day replace this with a more specific query that fetches the exact pipeline by id
        elasticsearchService.fetchAll(PIPELINES_INDEX).compose(pipelines->
                Future.succeededFuture(pipelines.stream()
                        .filter(p->p.getString("id").equals(pipelineId.toString()))
                        .findFirst().get()
                )).compose(pipeline->elasticsearchService.fetchAll(pipeline.getString("timelineIndex")))
                .onSuccess(timelines->simpleJsonListResponse(timelines, rc)).onFailure(err->rc.fail(500, err))
        ;

    }

    void getPipelines(RoutingContext rc){
        elasticsearchService.fetchAll(PIPELINES_INDEX)
                .onSuccess(pipelines->simpleJsonListResponse(pipelines, rc));
    }

    void getDistances(RoutingContext rc){
        client.get(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST, DEEP_SERVICE_DISTANCES_ENDPOINT).rxSend().subscribe(
                result->{
                    rc.response().setStatusCode(200).end(result.bodyAsJsonObject().encode());
                }
        );
    }

    void createEmbeddings(RoutingContext rc){
        List<JsonObject> elementsToEmbed = new ArrayList<>();
        timelines.values().forEach(timeline->{
            // Create a composite ID for each request using <timelineId>#<index>
            String timelineId = timeline.getString("id");
            JsonArray timelineEntries = timeline.getJsonArray("data");
            List<JsonObject> processedEntries = timelineEntries.stream()
                    .map(o->(JsonObject)o)
                    .map(json->json.put("id", timelineId + "#" + json.getInteger("index")))
                    .peek(json->{
                        if(json.getJsonArray("terms").size() == 0) log.warn("{} has 0 terms...", json.getString("id"));
                    })
                    .filter(json->json.getJsonArray("terms").size() != 0) //Filter entities with 0 terms

                    .peek(json->log.info("Creating request for {}", json.getString("id")))
                    .collect(Collectors.toList());
            elementsToEmbed.addAll(processedEntries);
        });

        Future future = null;
        for (JsonObject element: elementsToEmbed){
            Promise requestPromise = Promise.promise();
            client.post(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST, DEEP_SERVICE_EMBEDDING_ENDPOINT)
                    .rxSendJsonObject(element).subscribe((result, err)->{
                        if(err!= null){
                            log.error(err.getMessage(), err);
                            return;
                        }

                        log.info("Embedding Response status code: {}", result.statusCode());
                        if(result.statusCode() == 201){
                            log.info("Created embedding for {}", result.bodyAsJsonObject().getString("id"));
                        }



                        requestPromise.complete();
                    });

            if(future == null) {
                future = requestPromise.future();
            }else{
                future.compose((op)->requestPromise.future());
            }
        }

        future.onComplete(op->log.info("Create embeddings complete"));

        rc.response().setStatusCode(200).end();
    }

    /**
     * Returns annotation data for a particular timeline id
     * @param rc
     */
    void getAnnotation(RoutingContext rc){
        UUID timelineId = UUID.fromString(rc.pathParam("timelineId"));

        Optional<JsonObject> annotation = annotations.values()
                .stream()
                .filter(json->json.containsKey("id") && json.getString("id").equals(timelineId.toString()))
                .findFirst();

        if(annotation.isPresent()) rc.response().putHeader("Content-Type", "application/json").end(annotation.get().encode());
        else rc.fail(404);

    }

    /**
     * Update annotation data for a particular timeline id.
     * TODO: Probably should validate some of this huh?
     * @param rc
     */
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
     * Retrieves timeline entities from all timelines subject to some filtering.
     * @param rc
     */
    void getTimelineEntities(RoutingContext rc){

        List<String> symbols = rc.queryParam("symbol");

        JsonArray result = null;
        if(symbols.size() > 0){
            result = getEntities(new EntityFilters.hasSymbolAndHasTerms(symbols));
        }else{
            result = getEntities(new EntityFilters.hasTerms());
        }

        rc.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                .end(
                    result.encode()
                );


    }

    private JsonArray getEntities(Predicate<JsonObject> filter){
        JsonArray result = new JsonArray();
        timelines.values().forEach(timeline->{
            ListIterator entityIterator = timeline.getJsonArray("data").stream().toList().listIterator();
            while (entityIterator.hasNext()){
                var index = entityIterator.nextIndex();
                JsonObject entity = (JsonObject) entityIterator.next();
                //If a timeline entity record doesn't have an id, create it
                if(!entity.containsKey("id")){
                    entity.put("id", timeline.getString("id") + "#" + index);
                }
                if (filter.test(entity)){
                    result.add(entity);
                }

            }
        });
        return result;
    }



    /**
     * Return a list of timelines available on the server.
     * @param rc
     */
    void getTimelinesOld(RoutingContext rc){
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

    /**
     * Only returns timelines with entities and for which all entities have terms.
     * @return
     */
    void getCuratedTimelines(RoutingContext rc){
        rc.response().putHeader("Content-Type", "application/json")
                .end(
                        timelines.entrySet().stream()
                                .map(entry->entry.getValue())
                                .filter(timeline->
                                        timeline.getJsonArray("data").size() > 0 ||
                                                timeline.getJsonArray("data").stream().map(o->(JsonObject)o).filter(new EntityFilters.doesNotHaveTerms()).findAny().isEmpty()
                                )
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily()
                );

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

        //TODO: Fix this
        File timelinesDir = null;
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

    void simpleJsonListResponse(List<JsonObject> data, RoutingContext rc){
        JsonArray response = data.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
        rc.response().setStatusCode(200).end(response.encode());
    }

}
