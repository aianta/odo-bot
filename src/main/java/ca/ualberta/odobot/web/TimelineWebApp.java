package ca.ualberta.odobot.web;


import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import io.reactivex.rxjava3.core.Completable;

import io.vertx.core.Future;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import io.vertx.rxjava3.core.AbstractVerticle;

import io.vertx.rxjava3.core.http.HttpServer;


import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;

import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.handler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class TimelineWebApp extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimelineWebApp.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8080;


    private ElasticsearchService elasticsearchService;

    HttpServer server;
    Router mainRouter;
    Router api;

    WebClient client;


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
            api.route().method(HttpMethod.GET).path("/pipelines/:pipelineId/timelines").handler(this::getTimelines);

            api.route().method(HttpMethod.GET).path("/executions").handler(this::getExecutions);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/*").handler(this::resolveExecution);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/*").handler(this::resolvePipeline);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/timelines").handler(this::resolveTimelines);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/timelines").handler(this::getExecutionTimelines);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/timelineEntities").handler(this::resolveTimelineEntities);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/timelineEntities").handler(this::getTimelineEntities);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/activityLabels").handler(this::resolveActivityLabels);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/activityLabels").handler(this::getActivityLabels);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/activity/:label/*").handler(this::resolveActivityLabels);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/activity/:label/*").handler(this::resolveTimelineEntities);
            api.route().method(HttpMethod.GET).path("/executions/:executionId/activity/:label/timelineEntities").handler(this::lookupActivityLabel);

            api.route().failureHandler(rc->{
                log.error(rc.failure().getMessage(), rc.failure());
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

    void lookupActivityLabel(RoutingContext rc){
        String labelToLookup = rc.pathParam("label");

        JsonObject mappings = ((JsonObject)rc.get("activityLabels")).getJsonObject("mappings");
        List<JsonObject> entities = rc.get("entities");

        Set<String> entityIdsWithSpecifiedLabel = new HashSet<>();
        mappings.forEach(entry->{
            if(((String)entry.getValue()).equals(labelToLookup)){
                entityIdsWithSpecifiedLabel.add(entry.getKey());
            }
        });

        JsonArray response = entities.stream().filter(e->entityIdsWithSpecifiedLabel.contains(e.getString("id"))).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
        rc.response().setStatusCode(200).end(response.encode());
    }

    void getActivityLabels(RoutingContext rc){
        rc.response().setStatusCode(200).end(((JsonObject)rc.get("activityLabels")).encode());
    }

    void resolveActivityLabels(RoutingContext rc){
        JsonObject execution = rc.get("execution");
        JsonObject pipeline = rc.get("pipeline");

        String activityLabelingId = execution.getString("activityLabelingId");
        elasticsearchService.fetchAll(pipeline.getString("activityLabelIndex")).compose(
                activityLabelings->
                        Future.succeededFuture(activityLabelings.stream().filter(l->l.getString("id").equals(activityLabelingId)).findFirst()
                        .orElseThrow(()->new RuntimeException("Could not find activity labelling " + activityLabelingId + " for execution " + execution.getString("id") + " in pipeline " +  pipeline.getString("id"))))
        ).onSuccess(activityLabeling->{
            rc.put("activityLabels", activityLabeling);
            rc.next();
        }).onFailure(err->rc.fail(500, err));
    }

    void resolveTimelineEntities(RoutingContext rc){
        JsonObject execution = rc.get("execution");
        JsonObject pipeline = rc.get("pipeline");

        Set<String> entityIds = execution.getJsonArray("entityIds").stream().map(o->(String)o).collect(Collectors.toSet());

        elasticsearchService.fetchAll(pipeline.getString("timelineEntityIndex"))
                .compose(entities->Future.succeededFuture(entities.stream().filter(e->entityIds.contains(e.getString("id"))).collect(Collectors.toList())))
                .onSuccess(entities->{
                    rc.put("entities", entities);
                    rc.next();
                })
                .onFailure(err->rc.fail(500, err))
        ;
    }

    void resolveTimelines(RoutingContext rc){
        JsonObject execution = rc.get("execution");
        JsonObject pipeline = rc.get("pipeline");

        //Create a set of timeline ids involved in this execution
        Set<String> timelineIds = execution.getJsonArray("timelineIds").stream().map(o->(String)o).collect(Collectors.toSet());

        elasticsearchService.fetchAll(pipeline.getString("timelineIndex")) //Fetch all the timelines for this pipeline
                .onSuccess(timelines->{
                    List<JsonObject> result = timelines.stream().filter(t->timelineIds.contains(t.getString("id"))).collect(Collectors.toList());
                    rc.put("timelines", result);
                    rc.next();
                }) //Return only the timelines involved in this execution
                .onFailure(err->rc.fail(500, err));


    }

    void resolvePipeline(RoutingContext rc){
        JsonObject execution = rc.get("execution");

        elasticsearchService.fetchAll(PIPELINES_INDEX) //Fetch all pipelines
                .compose(pipelines->Future.succeededFuture(pipelines.stream()
                        .filter(p->p.getString("id").equals(execution.getString("pipelineId"))).findFirst()
                        .orElseThrow(
                                ()->new RuntimeException("PipelineId: " + execution.getString("pipelineId") +  " for execution: " +  execution.getString("id") + " could not be found!")))) //Filter out all pipelines except the one for this execution
                .onSuccess(pipeline->{
                    rc.put("pipeline", pipeline);
                    rc.next();
                }).onFailure(err->rc.fail(500, err));
    }

    void resolveExecution(RoutingContext rc){
        String executionId = rc.pathParam("executionId");
        elasticsearchService.fetchAll(EXECUTIONS_INDEX) //Fetch all executions
                .compose(executions->Future.succeededFuture(executions.stream().filter(e->e.getString("id").equals(executionId.toString())).findFirst()
                        .orElseThrow(()->new RuntimeException("Could not find execution with id " + executionId)))) //Filter out the execution for this request
                .onSuccess(execution->{
                    rc.put("execution", execution);
                    rc.next();
                })
                .onFailure(err->rc.fail(500, err));

    }

    void getExecutions(RoutingContext rc){
        elasticsearchService.fetchAll(EXECUTIONS_INDEX)
                .onSuccess(executions->simpleJsonListResponse(executions, rc))
                .onFailure(err->rc.fail(500,err));
    }

    void getExecutionTimelines(RoutingContext rc){
        List<JsonObject> timelines = rc.get("timelines");
        simpleJsonListResponse(timelines, rc);
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


    /**
     * Retrieves timeline entities from all timelines subject to some filtering.
     * @param rc
     */
    void getTimelineEntities(RoutingContext rc){
       List<JsonObject> entities = rc.get("entities");
       simpleJsonListResponse(entities, rc);
    }


    void simpleJsonListResponse(List<JsonObject> data, RoutingContext rc){
        JsonArray response = data.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
        rc.response().setStatusCode(200).end(response.encode());
    }

}
