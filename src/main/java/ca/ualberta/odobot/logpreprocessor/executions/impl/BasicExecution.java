package ca.ualberta.odobot.logpreprocessor.executions.impl;

import ca.ualberta.odobot.logpreprocessor.executions.ExternalArtifact;
import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecutionStatus;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@DataObject
public class BasicExecution implements PreprocessingPipelineExecution {

    public static DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    Map<String, UUID> indexToTimelineMap = new HashMap<>();
    Map<UUID, String> timelineToIndexMap = new HashMap<>();
    List<String> entityIds = new ArrayList<>();
    UUID activityLabelingId;
    ExternalArtifact xes;
    List<ExternalArtifact> visualizations = new ArrayList<>();
    List<ExternalArtifact> clusteringResults = new ArrayList<>();
    Instant startTimestamp;
    Instant endTimestamp;
    UUID id;
    String pipelineClass;
    UUID pipelineId;
    String dataPath;

    PreprocessingPipelineExecutionStatus status = new AbstractPreprocessingPipelineExecutionStatus.Initialized();



    public BasicExecution(){};

    public BasicExecution(JsonObject input){
        this.id = UUID.fromString(input.getString("id"));
        this.pipelineId = UUID.fromString(input.getString("pipelineId"));

        this.dataPath = input.containsKey("dataPath")?input.getString("dataPath"):null;

        JsonObject indexTimelineMappings = input.getJsonObject("indexTimelineMappings");
        indexTimelineMappings.forEach(entry->{
            String index = entry.getKey();
            UUID timelineId = UUID.fromString((String)entry.getValue());
            this.indexToTimelineMap.put(index, timelineId);
            this.timelineToIndexMap.put(timelineId, index);
        });

        JsonArray entityIds = input.getJsonArray("entityIds");
        entityIds.forEach(string->this.entityIds.add((String)string));
        this.activityLabelingId = input.containsKey("activityLabelingId")?UUID.fromString(input.getString("activityLabelingId")):null;
        this.xes = input.containsKey("xes")?ExternalArtifact.fromJson(input.getJsonObject("xes")):null;

        JsonArray visualizations = input.getJsonArray("visualizations");
        this.visualizations = visualizations.stream().map(o->(JsonObject)o).map(ExternalArtifact::fromJson).collect(Collectors.toList());

        JsonArray clusteringResults = input.getJsonArray("clusteringResults");
        this.clusteringResults = clusteringResults.stream().map(o->(JsonObject)o).map(ExternalArtifact::fromJson).collect(Collectors.toList());

        this.startTimestamp = input.containsKey("startTimestamp")?Instant.ofEpochMilli(input.getLong("startTimestamp")):null;
        this.endTimestamp = input.containsKey("endTimestamp")?Instant.ofEpochMilli(input.getLong("endTimestamp")):null;
        this.status = AbstractPreprocessingPipelineExecutionStatus.fromJson(input.getJsonObject("status"));
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public UUID getTimeline(String index) {
        return indexToTimelineMap.get(index);
    }

    @Override
    public String getIndex(UUID timelineId) {
        return timelineToIndexMap.get(timelineId);
    }

    public void start(){
        startTimestamp = Instant.now();
    }

    public void stop(){
        endTimestamp = Instant.now();
    }

    @Override
    public Set<String> inputIndices() {
        return indexToTimelineMap.keySet();
    }

    @Override
    public Set<UUID> timelineIds() {
        return timelineToIndexMap.keySet();
    }

    @Override
    public List<String> timelineEntityIds() {
        return entityIds;
    }

    @Override
    public UUID activityLabelingId() {
        return activityLabelingId;
    }

    @Override
    public ExternalArtifact xes() {
        return xes;
    }

    @Override
    public List<ExternalArtifact> processModelVisualizations() {
        return visualizations;
    }

    @Override
    public List<ExternalArtifact> clusteringResults(){
        return clusteringResults;
    }

    @Override
    public ExternalArtifact processModel() {
        return null;
    }

    @Override
    public UUID processModelStatsId() {
        return null;
    }

    @Override
    public long startTimestamp() {
        return startTimestamp.toEpochMilli();
    }

    @Override
    public long endTimestamp() {
        return endTimestamp.toEpochMilli();
    }

    public String dataPath(){
        return dataPath;
    }

    @Override
    public PreprocessingPipelineExecutionStatus status() {
        return status;
    }

    @Override
    public UUID pipelineId() {
        return pipelineId;
    }

    public String pipelineClass(){return pipelineClass;}

    @Override
    public JsonObject toJson() {
        JsonObject result = new JsonObject()
                .put("id", id.toString())
                .put("pipelineId", pipelineId().toString())
                .put("pipelineClass", pipelineClass())
                .put("indexTimelineMappings", indexTimelineJson())
                .put("inputIndices", inputIndices().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("timelineIds", timelineIds().stream().map(UUID::toString).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("entityIds", entityIds.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("visualizations", visualizations.stream().map(record->record.toJson()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("clusteringResults", clusteringResults().stream().map(record->record.toJson()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("status", status().toJson());

        if(dataPath() != null){
            result.put("dataPath", dataPath());
        }

        if(activityLabelingId != null){
            result.put("activityLabelingId", activityLabelingId.toString());
        }
        if(xes != null){
            result.put("xes", xes.toJson());
        }
        if(startTimestamp != null){
            result.put("startTimestamp", startTimestamp())
                    .put("_startTimestamp", ZonedDateTime.ofInstant(startTimestamp, ZoneId.systemDefault()).format(timeFormatter));
        }

        if(endTimestamp != null){
            result.put("endTimestamp", endTimestamp())
                    .put("_endTimestamp", ZonedDateTime.ofInstant(endTimestamp, ZoneId.systemDefault()).format(timeFormatter));
        }

        return result;
    }

    @Override
    public void registerTimeline(String index, UUID timeline) {
        indexToTimelineMap.put(index, timeline);
        timelineToIndexMap.put(timeline, index);
    }

    private JsonObject indexTimelineJson(){
        JsonObject result = new JsonObject();
        indexToTimelineMap.forEach((index,id)->result.put(index, id.toString()));
        return result;
    }

    public  void setPipelineClass(String pipelineClass){
        this.pipelineClass = pipelineClass;
    }

    public void setActivityLabelingId(UUID activityLabelingId) {
        this.activityLabelingId = activityLabelingId;
    }

    public void setXes(ExternalArtifact xes) {
        this.xes = xes;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setPipelineId(UUID pipelineId) {
        this.pipelineId = pipelineId;
    }

    public void setStatus(PreprocessingPipelineExecutionStatus status) {
        this.status = status;
    }

    public void setDataPath(String dataPath){
        this.dataPath = dataPath;
    }
}
