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

    void executeHandler(RoutingContext rc);

    void timelinesHandler(RoutingContext rc);

    void purgePipeline(RoutingContext rc);

}