package ca.ualberta.odobot.logpreprocessor;


import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;

import java.util.List;
import java.util.UUID;

public interface PreprocessingPipeline {

    String name();

    String slug();

    UUID id();

    List<PreprocessingPipelineExecution> history();

    String timelineIndex();

    String timelineEntityIndex();

    String activityLabelIndex();

    String processModelStatsIndex();


    void executeHandler(RoutingContext rc);

    void timelinesHandler(RoutingContext rc);

    void timelineEntitiesHandler(RoutingContext rc);

    void activityLabelsHandler(RoutingContext rc);

    void xlogHandler(RoutingContext rc);

    void processModelVisualizationHandler(RoutingContext rc);

    void processModelStatsHandler(RoutingContext rc);

    void processModelHandler(RoutingContext rc);


}
