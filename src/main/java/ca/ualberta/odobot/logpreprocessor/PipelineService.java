package ca.ualberta.odobot.logpreprocessor;


import io.vertx.rxjava3.ext.web.RoutingContext;

public interface PipelineService {

    String slug();

    void processModelVisualizationHandler(RoutingContext rc);

    void processModelStatsHandler(RoutingContext rc);

    void processModelHandler(RoutingContext rc);

    void xesHandler(RoutingContext rc);

    void activityLabelsHandler(RoutingContext rc);

    void timelineEntitiesHandler(RoutingContext rc);

    void timelinesHandler(RoutingContext rc);

    void semanticTraceHandler(RoutingContext rc);

    void captureTrainingMaterialsHandler(RoutingContext rc);

    void makeTrainingExemplarsHandler(RoutingContext rc);

    void purgePipeline(RoutingContext rc);

    void transienceHandler(RoutingContext rc);

    PipelinePersistenceLayer persistenceLayer();

    void beforeExecution(RoutingContext rc);

    void afterExecution(RoutingContext rc);

}
