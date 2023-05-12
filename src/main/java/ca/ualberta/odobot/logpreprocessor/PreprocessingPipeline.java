package ca.ualberta.odobot.logpreprocessor;


import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;

import java.io.File;
import java.util.List;
import java.util.Map;
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


    Future<List<Timeline>> makeTimelines(Map<String, List<JsonObject>> eventsMap);



    Future<List<TimelineEntity>> makeEntities(List<Timeline> timelines);



    Future<JsonObject> makeActivityLabels(List<TimelineEntity> entities);



    Future<File> makeXes(JsonArray timelines, JsonObject activityLabels);






}
