package ca.ualberta.odobot.logpreprocessor;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.executions.impl.AbstractPreprocessingPipelineExecutionStatus;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import ca.ualberta.odobot.logpreprocessor.impl.*;
import ca.ualberta.odobot.semanticflow.model.Timeline;

import co.elastic.clients.elasticsearch.nodes.Http;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import io.vertx.serviceproxy.ServiceBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class LogPreprocessor extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(LogPreprocessor.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8078;

    private static ElasticsearchService elasticsearchService;

    private Set<Class> mountedPipelines = new HashSet<>();

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

                    try{
                        UUID id = UUID.fromString(record.getString("id"));
                        String slug = record.getString("slug");
                        String name = record.getString("name");
                        String className = record.getString("class");

                        Class clazz = Class.forName(className);
                        Constructor constructor = clazz.getConstructor(Vertx.class, UUID.class, String.class, String.class );

                        PreprocessingPipeline pipeline = (PreprocessingPipeline) constructor.newInstance(vertx, id, slug, name);


                        mountPipeline(api, pipeline);
                    }catch (ClassNotFoundException | NoSuchMethodException e){
                        log.error(e.getMessage(), e);
                    } catch (InvocationTargetException e) {
                        log.error(e.getMessage(), e);
                    } catch (InstantiationException e) {
                        log.error(e.getMessage(), e);
                    } catch (IllegalAccessException e) {
                        log.error(e.getMessage(), e);
                    }



                });

                //ADD NEW PIPELINES HERE
//                PreprocessingPipeline hierarchicalPipeline = new HierarchicalClusteringPipeline(
//                        vertx, UUID.randomUUID(), "hierarchical-v1", "Hierarchical clustering technique that blends domain knowledge from the DOM with unsupervised learning to determine activity labels. "
//                );
//
//                mountPipeline(api, hierarchicalPipeline);
//                elasticsearchService.saveIntoIndex(List.of(hierarchicalPipeline.toJson()),PIPELINES_INDEX).onSuccess(saved->log.info("saved effect overhaul pipeline to index"));



            }else{ //Otherwise create a new pipeline
                //Create simple preprocessing pipeline
                PreprocessingPipeline simplePipeline = new SimplePreprocessingPipeline(
                        vertx, UUID.randomUUID(), "test", "test pipeline"
                );

                PreprocessingPipeline enhancedEmbeddingsPipeline = new EnhancedEmbeddingPipeline(
                        vertx, UUID.randomUUID(), "embeddings-v2", "First pipeline to use the /activitylabels/v2/ deep service endpoint"
                );

                PreprocessingPipeline tfidfPipeline = new TFIDFPipeline(
                        vertx, UUID.randomUUID(), "activity-labels-v3", "First pipeline to use tfidf /activitylabels/v3/ deep service endpoint"
                );

                PreprocessingPipeline temporalPipeline = new TemporalPipeline(
                        vertx, UUID.randomUUID(), "temporal-v1", "First pipeline to add info about previous and next entities."
                );

                PreprocessingPipeline tfidfTemporalPipeline = new TFIDFPipeline(
                        vertx, UUID.randomUUID(), "tfidf-temporal-v1", "First tfidf pipeline to add info about previous and next entities."
                );

                PreprocessingPipeline effectOverhaulPipeline = new EffectOverhaulPipeline(
                        vertx, UUID.randomUUID(), "effect-overhaul-v1", "Split Effect representation in 'added' and 'removed' lists to allow more meaningful embedding."
                );

                PreprocessingPipeline hierarchicalPipeline = new HierarchicalClusteringPipeline(
                        vertx, UUID.randomUUID(), "hierarchical-v1", "Hierarchical clustering technique that blends domain knowledge from the DOM with unsupervised learning to determine activity labels. "
                );

                elasticsearchService.saveIntoIndex(List.of(
                        simplePipeline.toJson(),
                        enhancedEmbeddingsPipeline.toJson(),
                        tfidfPipeline.toJson(),
                        temporalPipeline.toJson(),
                        tfidfTemporalPipeline.toJson(),
                        effectOverhaulPipeline.toJson(),
                        hierarchicalPipeline.toJson()
                ), PIPELINES_INDEX).onSuccess(done->{
                    log.info("Registered pipeline(s) in elasticsearch");
                });

                mountPipeline(api, enhancedEmbeddingsPipeline);
                mountPipeline(api, simplePipeline);
                mountPipeline(api, tfidfPipeline);
                mountPipeline(api, temporalPipeline);
                mountPipeline(api, tfidfTemporalPipeline);
                mountPipeline(api, effectOverhaulPipeline);

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
        if(mountedPipelines.contains(pipeline.getClass())){
            log.info("Pipeline with class {} already mounted.", pipeline.getClass().getName());
            return;
        }
        mountedPipelines.add(pipeline.getClass());
        log.info("Mounting [{}] {} - {}", pipeline.id().toString(), pipeline.slug(), pipeline.getClass().getName() );

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

        Route entitiesRoute = router.route().method(HttpMethod.GET).path("/preprocessing/pipelines/" + pipeline.slug() + "/entities");
        entitiesRoute.handler(pipeline::timelinesHandler);
        entitiesRoute.handler(pipeline::timelineEntitiesHandler);
        entitiesRoute.handler(rc->{
            List<JsonObject> entities = rc.get("entities");
            JsonObject responseData = new JsonObject().put("entities", entities.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(responseData.encode());
        });

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
