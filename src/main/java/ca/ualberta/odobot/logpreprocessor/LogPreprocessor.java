package ca.ualberta.odobot.logpreprocessor;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.executions.impl.AbstractPreprocessingPipelineExecutionStatus;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import ca.ualberta.odobot.logpreprocessor.impl.SimplePreprocessingPipeline;
import ca.ualberta.odobot.semanticflow.model.Timeline;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import io.vertx.serviceproxy.ServiceBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.List;
import java.util.UUID;


import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class LogPreprocessor extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(LogPreprocessor.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8078;

    private static ElasticsearchService elasticsearchService;

    Router mainRouter;
    Router api;
    HttpServer server;

    public Completable rxStart(){
        //Init Http Server
        HttpServerOptions options = new HttpServerOptions()
                .setHost(HOST)
                .setPort(PORT)
                .setSsl(false);

        server = vertx.createHttpServer(options);
        mainRouter = Router.router(vertx);
        api = Router.router(vertx);

        //Init Elasticsearch Service
        elasticsearchService = ElasticsearchService.create(vertx.getDelegate(), "localhost", 9200);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .register(ElasticsearchService.class, elasticsearchService);


        /**
         * Load up pipelines as defined in elasticsearch records.
         * NOTE: Experimental
         */
        elasticsearchService.fetchAll(PIPELINES_INDEX)
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(pipelineRecords->{
            if(pipelineRecords.size() > 0){ //If we found pipeline records.
                pipelineRecords.forEach(record->{
                    UUID id = UUID.fromString(record.getString("id"));
                    String slug = record.getString("slug");
                    String name = record.getString("name");

                    PreprocessingPipeline pipeline = new SimplePreprocessingPipeline(
                            vertx, id, slug, name
                    );

                    mountPipeline(api, pipeline);
                });
            }else{ //Otherwise create a new pipeline
                //Create simple preprocessing pipeline
                PreprocessingPipeline simplePipeline = new SimplePreprocessingPipeline(
                        vertx, UUID.randomUUID(), "test", "test pipeline"
                );

                elasticsearchService.saveIntoIndex(List.of(simplePipeline.toJson()), PIPELINES_INDEX).onSuccess(done->{
                    log.info("Registered pipeline in elasticsearch");
                });

                mountPipeline(api, simplePipeline);
            }


        });


        //Define API routes
        api.route().method(HttpMethod.DELETE).path("/indices/:target").handler(this::clearIndex);
        api.route().method(HttpMethod.DELETE).path("/indices").handler(this::clearIndices);

        //Mount handlers to main router
        mainRouter.route().handler(LoggerHandler.create());
        mainRouter.route().handler(BodyHandler.create());
        mainRouter.route().handler(rc->{rc.response().putHeader("Access-Control-Allow-Origin", "*"); rc.next();});
        mainRouter.route(API_PATH_PREFIX).subRouter(api);

        server.requestHandler(mainRouter).listen(PORT);
        log.info("LogPreprocessor Server started on port: {}", PORT);

        return super.rxStart();
    }

    /**
     * Hooks up pipeline handlers to routes on given router.
     * @param router router to mount pipeline to.
     * @param pipeline the pipeline to mount
     */
    private void mountPipeline(Router router, PreprocessingPipeline pipeline){
        PipelinePersistenceLayer persistenceLayer = pipeline.persistenceLayer();
        Route executeRoute = api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/execute");

        executeRoute.handler(pipeline::beforeExecution);
        executeRoute.handler(pipeline::transienceHandler);
        executeRoute.handler(pipeline::timelinesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(pipeline::timelineEntitiesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(pipeline::activityLabelsHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(pipeline::xesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(pipeline::processModelVisualizationHandler);
        executeRoute.handler(pipeline::afterExecution);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(rc->{
//            rc.response().setStatusCode(200).putHeader("Content-Type", "image/png").end((Buffer)rc.get("bpmnVisualization"));
            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(((BasicExecution)rc.get("metadata")).toJson().encode());
        });
        executeRoute.failureHandler(rc->{
            log.error("Execute Route failure handler invoked!");
            log.error(rc.failure().getMessage(), rc.failure());
            BasicExecution execution = rc.get("metadata");
            execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.Failed(execution.status().data().mergeIn(new JsonObject().put("error", rc.failure().getMessage()))));
            execution.stop();
            log.info("Updated basic execution: {}", execution.id().toString());
            rc.next();
        });
        executeRoute.failureHandler(persistenceLayer::persistenceHandler); //Update record keeping for failure.
        executeRoute.failureHandler(rc->rc.response().setStatusCode(500).end(rc.failure().getMessage()));

        router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/timelines").handler(pipeline::timelinesHandler);
        router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/timelines").handler(rc->{
            List<Timeline> timelines = rc.get("timelines");
            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(timelines.stream().map(Timeline::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode());
        });

        router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/activityLabels").handler(pipeline::activityLabelsHandler);
        router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/xes").handler(pipeline::xesHandler);
        router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/visualization").handler(pipeline::processModelVisualizationHandler);
        router.route().method(HttpMethod.DELETE).path("/preprocessing/pipelines/" + pipeline.slug() + "/purge").handler(pipeline::purgePipeline);

    }

    /**
     * Clears the {@link Constants#EXECUTIONS_INDEX} and {@link Constants#PIPELINES_INDEX} indices.
     * @param rc
     */
    private void clearIndices(RoutingContext rc){
        elasticsearchService.deleteIndex(EXECUTIONS_INDEX)
                .compose(mapper->elasticsearchService.deleteIndex(PIPELINES_INDEX))
                .onSuccess(done->rc.response().setStatusCode(200).end())
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    rc.response().setStatusCode(500).end();
                });
    }

    //Clears a particular index
    private void clearIndex(RoutingContext rc){
        final String indexToDelete = rc.request().params().get("target");
        elasticsearchService.deleteIndex(indexToDelete)
                .onSuccess(done->rc.response().setStatusCode(200).end())
                .onFailure(err->{
                    log.error(err.getMessage(),err);
                    rc.response().setStatusCode(500).end();
                })
        ;
    }


}
