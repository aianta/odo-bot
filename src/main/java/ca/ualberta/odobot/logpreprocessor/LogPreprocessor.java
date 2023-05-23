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
    private static final String TIMELINES_INDEX = "timelines";
    private static final String TIMELINE_ENTITIES_INDEX = "timeline-entities";



    private static ElasticsearchService elasticsearchService;

    Router mainRouter;
    Router api;
    HttpServer server;

    public Completable rxStart(){


        //Init Elasticsearch Service
        elasticsearchService = ElasticsearchService.create(vertx.getDelegate(), "localhost", 9200);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .register(ElasticsearchService.class, elasticsearchService);

        //Create simple preprocessing pipeline
        PreprocessingPipeline simplePipeline = new SimplePreprocessingPipeline(
                vertx, UUID.randomUUID(), "test", "test pipeline"
        );


        //Init Http Server
        HttpServerOptions options = new HttpServerOptions()
                .setHost(HOST)
                .setPort(PORT)
                .setSsl(false);

        server = vertx.createHttpServer(options);
        mainRouter = Router.router(vertx);
        api = Router.router(vertx);

        //Define API routes
        api.route().method(HttpMethod.DELETE).path("/indices/:target").handler(this::clearIndex);
        api.route().method(HttpMethod.DELETE).path("/indices").handler(this::clearIndices);

        PipelinePersistenceLayer persistenceLayer = simplePipeline.persistenceLayer();
        Route executeRoute = api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/execute");

        executeRoute.handler(simplePipeline::beforeExecution);
        executeRoute.handler(simplePipeline::transienceHandler);
        executeRoute.handler(simplePipeline::timelinesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(simplePipeline::timelineEntitiesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(simplePipeline::activityLabelsHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(simplePipeline::xesHandler);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(simplePipeline::processModelVisualizationHandler);
        executeRoute.handler(simplePipeline::afterExecution);
        executeRoute.handler(persistenceLayer::persistenceHandler);
        executeRoute.handler(rc->{
            rc.response().setStatusCode(200).putHeader("Content-Type", "image/png").end((Buffer)rc.get("bpmnVisualization"));
        });
        executeRoute.failureHandler(rc->{

            log.error(rc.failure().getMessage(), rc.failure());
            rc.response().setStatusCode(500).end(rc.failure().getMessage());
            BasicExecution execution = rc.get("metadata");
            execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.Failed(execution.status().data().mergeIn(new JsonObject().put("error", rc.failure().getMessage()))));
        });
        executeRoute.failureHandler(persistenceLayer::persistenceHandler); //Update record keeping for failure.


        api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/timelines").handler(simplePipeline::timelinesHandler);
        api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/timelines").handler(rc->{
            List<Timeline> timelines = rc.get("timelines");
            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(timelines.stream().map(Timeline::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode());
        });

        api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/activityLabels").handler(simplePipeline::activityLabelsHandler);
        api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/xes").handler(simplePipeline::xesHandler);
        api.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/visualization").handler(simplePipeline::processModelVisualizationHandler);
        api.route().method(HttpMethod.DELETE).path("/preprocessing/pipelines/" + simplePipeline.slug() + "/purge").handler(simplePipeline::purgePipeline);
        //TODO - pipelines aren't quite mature enough for this yet...
//        api.route().method(HttpMethod.POST).path("/preprocessing/pipeline").handler(this::createPipeline);

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
     * Clears the {@link #TIMELINES_INDEX} and {@link #TIMELINE_ENTITIES_INDEX} indices.
     * @param rc
     */
    private void clearIndices(RoutingContext rc){
        elasticsearchService.deleteIndex(TIMELINES_INDEX)
                .compose(mapper->elasticsearchService.deleteIndex(TIMELINE_ENTITIES_INDEX))
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
