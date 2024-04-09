package ca.ualberta.odobot.logpreprocessor;


import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.semanticflow.model.StateSample;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.semanticflow.model.semantictrace.SemanticTrace;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PreprocessingPipeline extends PipelineService {

    JsonObject toJson();

    String name();

    String slug();

    UUID id();

    List<PreprocessingPipelineExecution> history();

    String timelineIndex();

    String timelineEntityIndex();

    String activityLabelIndex();

    String processModelStatsIndex();


    Future<List<Timeline>> makeTimelines(Map<String, List<JsonObject>> eventsMap);

    Future<Timeline> makeTimeline(String flightName, List<JsonObject> events);

    Future<SemanticTrace> makeSemanticTrace(Timeline timeline);

    Future<List<StateSample>> extractStateSamples(Timeline timeline, String datasetName);

    Future<List<TrainingMaterials>> captureTrainingMaterials(Timeline timeline, String datasetName);

    Future<List<TrainingExemplar>> makeTrainingExemplars(List<TrainingMaterials> materials);


    Future<List<TimelineEntity>> makeEntities(List<Timeline> timelines);



    Future<JsonObject> makeActivityLabels(List<JsonObject> entities);



    Future<File> makeXes(JsonArray timelines, JsonObject activityLabels);


    Future<Map<String,Buffer>> makeModelVisualization(File xes);




}
