package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.PipelinePersistenceLayer;
import ca.ualberta.odobot.logpreprocessor.PipelineService;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.exceptions.BadRequest;
import ca.ualberta.odobot.logpreprocessor.executions.ExternalArtifact;
import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.logpreprocessor.executions.impl.AbstractPreprocessingPipelineExecutionStatus;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;


import ca.ualberta.odobot.semanticflow.model.Timeline;

import io.vertx.core.CompositeFuture;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;

import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

/**
 * Provide base/common/core fields, resources, and services to all pipelines.
 */
public abstract class AbstractPreprocessingPipeline implements PreprocessingPipeline, PipelineService {

    private static final Logger log = LoggerFactory.getLogger(AbstractPreprocessingPipeline.class);

    protected ElasticsearchService elasticsearchService;
    protected Vertx vertx;

    WebClient client;

    private String name;
    private String slug;
    private UUID id;
    private List<PreprocessingPipelineExecution> history = new ArrayList<>();
    private String timelineIndex;
    private String timelineEntityIndex;
    private String activityLabelIndex;
    private String processModelStatsIndex;

    public AbstractPreprocessingPipeline(Vertx vertx, String slug){
        this.vertx = vertx;
        /**
         * Pipelines use proxies to interact with services allowing for better use of resources
         * across a distributed deployment.
         */
        elasticsearchService = ElasticsearchService.createProxy(vertx.getDelegate(), ELASTICSEARCH_SERVICE_ADDRESS);


        client = WebClient.create(vertx);

        setTimelineIndex("timelines-" + slug);
        setTimelineEntityIndex("timeline-entities-"+slug);
        setActivityLabelIndex("activity-labels-"+slug);
        setSlug(slug);
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result  .put("name", name())
                .put("slug", slug())
                .put("id", id().toString())
                .put("timelineIndex", timelineIndex())
                .put("timelineEntityIndex", timelineEntityIndex())
                .put("activityLabelIndex", activityLabelIndex())
                .put("processModelStatsIndex", processModelStatsIndex())
        ;

        return result;
    }

    public AbstractPreprocessingPipeline setName(String name) {
        this.name = name;
        return this;
    }

    public AbstractPreprocessingPipeline setSlug(String slug) {
        this.slug = slug;
        return this;
    }

    public AbstractPreprocessingPipeline setId(UUID id) {
        this.id = id;
        return this;
    }

    public AbstractPreprocessingPipeline setHistory(List<PreprocessingPipelineExecution> history) {
        this.history = history;
        return this;
    }

    public AbstractPreprocessingPipeline setTimelineIndex(String timelineIndex) {
        this.timelineIndex = timelineIndex;
        return this;
    }

    public AbstractPreprocessingPipeline setTimelineEntityIndex(String timelineEntityIndex) {
        this.timelineEntityIndex = timelineEntityIndex;
        return this;
    }

    public AbstractPreprocessingPipeline setActivityLabelIndex(String activityLabelIndex) {
        this.activityLabelIndex = activityLabelIndex;
        return this;
    }

    public AbstractPreprocessingPipeline setProcessModelStatsIndex(String processModelStatsIndex) {
        this.processModelStatsIndex = processModelStatsIndex;
        return this;
    }

    public String name(){
        return this.name;
    }

    public String slug(){
        return this.slug;
    }

    public UUID id(){
        return this.id;
    }

    public List<PreprocessingPipelineExecution> history() {
        return history;
    }

    @Override
    public String timelineIndex() {
        return this.timelineIndex;
    }

    @Override
    public String timelineEntityIndex() {
        return this.timelineEntityIndex;
    }

    @Override
    public String activityLabelIndex() {
        return this.activityLabelIndex;
    }

    @Override
    public String processModelStatsIndex() {
        return this.processModelStatsIndex;
    }

    public void afterExecution(RoutingContext rc){
        BasicExecution execution = rc.get("metadata");
        execution.stop();
        execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.Complete(execution.status().data()));
        rc.next();
    }

    public void beforeExecution(RoutingContext rc){
        //Create metadata record for the execution
        BasicExecution execution = new BasicExecution();
        execution.setId(UUID.randomUUID());
        execution.setPipelineId(id());
        execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.InProgress());
        execution.status().data().put("step", "beforeExecution"); //Update execution step
        execution.start();
        rc.put("metadata", execution);
        rc.next();
    }

    @Override
    public void timelinesHandler(RoutingContext rc) {



        List<String> esIndices = rc.queryParam("index");
        rc.put("esIndices", esIndices);

        /**
         *  Event logs should be returned with the oldest event first. Create a JsonArray
         *  following the format described here: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html
         *  to enforce this.
         */
        JsonArray sortOptions = new JsonArray()
                .add(new JsonObject().put(TIMESTAMP_FIELD, "asc"));

        //Fetch the event logs corresponding with every index requested
        CompositeFuture.all(
                esIndices.stream().map(index->elasticsearchService.fetchAndSortAll(index, sortOptions)).collect(Collectors.toList())
        ).compose(future->{
            List<List<JsonObject>> eventlogs = future.list();

            //Build a map associating the logs with their elasticsearch indices
            Map<String,List<JsonObject>> eventlogsMap = new HashMap<>();
            for(int i = 0; i < eventlogs.size(); i++){
                eventlogsMap.put(esIndices.get(i), eventlogs.get(i));
            }
            rc.put("rawEventsMap", eventlogsMap);

            //Pass the eventlogMap to the implementing subclass for processing.
            return makeTimelines(eventlogsMap);

        }).onSuccess(timelines->{

            BasicExecution execution = rc.get("metadata");
            //Update execution metadata
            if(execution != null){
                execution.status().data().put("step", "timelinesHandler");
                timelines.forEach(timeline->execution.registerTimeline(timeline.getAnnotations().getString("source-index"),timeline.getId()));
            }


            //Put timelines in routing context for further processing or persistence layer.
            rc.put("timelines", timelines);
            rc.next();
        });

    }

    @Override
    public void timelineEntitiesHandler(RoutingContext rc) {

        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "timelineEntitiesHandler");
        }

        //Get timelines from the routing context
        List<Timeline> timelines = rc.get("timelines");
        //Convert them to JsonObjects
        List<JsonObject> json = timelines.stream().map(Timeline::toJson).collect(Collectors.toList());

        //Shuffle things around to extract all entities from all timelines
        List<JsonObject> entityJson = json.stream().map(timelineJson->
                timelineJson.getJsonArray("data").stream()
                        .map(o->(JsonObject)o)
                        .collect(Collectors.toList())

        ).collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);


        //Update execution metadata, log all entities used in this execution
        if(execution != null){
            entityJson.forEach(entity->execution.timelineEntityIds().add(entity.getString("id")));
        }


        //Put the extracted entities back into the routing context
        rc.put("entities", entityJson);
        rc.next();
    }

    @Override
    public void activityLabelsHandler(RoutingContext rc) {
        List<JsonObject> entities = rc.get("entities");

        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "activityLabelsHandler");
        }


        makeActivityLabels(entities).onSuccess(activityLabels->{
                    //Update execution metadata
                    if(execution != null){
                        execution.setActivityLabelingId(UUID.fromString(activityLabels.getString("id")));
                    }
                    rc.put("activities", activityLabels);
                    rc.next();
                })
                .onFailure(err->{
                    log.error(err.getMessage(), err);
                    rc.fail(500, err);
                });
    }

    @Override
    public void xesHandler(RoutingContext rc) {

        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "xesHandler");
        }

        JsonObject activities = rc.get("activities");
        List<Timeline> timelines = rc.get("timelines");
        JsonArray timelinesJson = timelines.stream().map(Timeline::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        makeXes(timelinesJson, activities).onSuccess(xesFile->{
            try{
                //Update execution metadata
                if(execution != null){
                    var xesPath = Path.of(execution.dataPath(), XES_FILE_NAME);
                    execution.setXes(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, xesPath.toString()));
                    Files.copy(xesFile.toPath(), xesPath);
                }
                rc.put("xesFile", xesFile);
                rc.next();
            }catch (IOException e){
                log.error(e.getMessage(), e);
                rc.fail(500, e);
            }

        }).onFailure(err->{
            rc.fail(500, err);
        });


    }

    @Override
    public void processModelVisualizationHandler(RoutingContext rc) {

        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step","processModelVisualizationHandler");
        }

        File xesInput = new File(Path.of(execution.dataPath(), XES_FILE_NAME).toString());


        makeModelVisualization(xesInput).onSuccess(
                visualization->{
                    //Update execution metadata
                    if (execution != null){
                        var bpmnPath = Path.of(execution.dataPath(), BPMN_FILE_NAME);
                        execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, bpmnPath.toString()));
                    }

                    rc.put("bpmnVisualization", visualization);
                    rc.next();

                }
        ).onFailure(err->{
            log.error(err.getMessage(),err);
            rc.fail(500, err);
        });

    }

    @Override
    public void processModelStatsHandler(RoutingContext rc) {
        rc.next();
    }

    @Override
    public void processModelHandler(RoutingContext rc) {
        rc.next();
    }

    protected void genericErrorHandler(Throwable e){
        log.error("Pipeline error!");
        log.error(e.getMessage(), e);
    }

    public void transienceHandler(RoutingContext rc)  {
        boolean isTransient = false; // Flag for whether to skip saving the resulting timeline in es.
        if(rc.request().params().contains("transient")){
            //Just some error handling
            if (rc.request().params().getAll("transient").size() > 1){
                rc.response().setStatusCode(400).end(new JsonObject().put("error", "cannot have multiple 'transient' parameters in request.").encode());
                throw new BadRequest("too many transient parameters. cannot have multiple 'transient' parameters in request.");
            }
            isTransient = Boolean.parseBoolean(rc.request().params().get("transient"));
        }

        rc.put("isTransient", isTransient);
        rc.next();
    }

    public PipelinePersistenceLayer persistenceLayer(){
        PipelinePersistenceLayer persistenceLayer = new PipelinePersistenceLayer();

        //Timeline persistence
        persistenceLayer.<List<Timeline>, BasicExecution>registerPersistence("timelines", (timelines, execution)->{
            //Convert to List<JsonObject> for transit over eventbus
            List<JsonObject> json = timelines.stream().map(Timeline::toJson).collect(Collectors.toList());

            final String timelinesString = timelines.stream().map(Timeline::getId).collect(StringBuilder::new, (sb, ele)->sb.append(ele.toString() + ", "), StringBuilder::append).toString();

            //Store using elasticsearch service
            elasticsearchService.saveIntoIndex(json, timelineIndex()).onSuccess(done->{
                log.info("Timelines {} persisted in elasticsearch index: {}", timelinesString, timelineIndex());
            }).onFailure(err->{
                log.error("Error persisting timelines {} into elasticsearch index: {}",timelinesString, timelineIndex());
                log.error(err.getMessage(), err);
            });
        }, PipelinePersistenceLayer.PersistenceType.ONCE);

        //Entity persistence
        persistenceLayer.<List<JsonObject>, BasicExecution>registerPersistence("entities", (entities, execution)->{
            final String entitiesString = entities.stream().map(entity->entity.getString("id")).collect(StringBuilder::new, (sb, id)->sb.append(id + ", "), StringBuilder::append).toString();
            elasticsearchService.saveIntoIndex(entities, timelineEntityIndex()).onSuccess(done->{
                log.info("Entities {} persisted in elasticsearch index: {}", entitiesString, timelineEntityIndex());
            }).onFailure(err->{
                log.error("Error persisting entities {} into elastic search index: {}", entitiesString,timelineEntityIndex());
                log.error(err.getMessage(), err);
            });
        }, PipelinePersistenceLayer.PersistenceType.ONCE);

        /**
         * Metadata {@link BasicExecution} persistence
         */
        persistenceLayer.<BasicExecution, BasicExecution>registerPersistence("metadata", (execution, ex)->{
            elasticsearchService.updateExecution(execution)
                    .onSuccess(done->log.info("Execution record updated!"))
                    .onFailure(err->log.error(err.getMessage(),err))
            ;

            try{
                var executionLocalRecordPath = Path.of(execution.dataPath(), EXECUTION_FILE_NAME);
                if(Files.exists(executionLocalRecordPath)){ //Clear any old execution record.
                    Files.delete(executionLocalRecordPath);
                }
                vertx.fileSystem().rxWriteFile(executionLocalRecordPath.toString(),Buffer.buffer(execution.toJson().encodePrettily())).subscribe();
            } catch (IOException ioException) {
                log.error("Error writing execution record to disk.");
                log.error(ioException.getMessage(), ioException);

            }

        }, PipelinePersistenceLayer.PersistenceType.ALWAYS);

        //ActivityLabeling persistence
        persistenceLayer.<JsonObject, BasicExecution>registerPersistence("activities", (activities, execution)->{
            elasticsearchService.saveIntoIndex(List.of(activities), activityLabelIndex())
                    .onSuccess(done->log.info("Activity labels persisted!"))
                    .onFailure(err->log.error(err.getMessage(), err));
        }, PipelinePersistenceLayer.PersistenceType.ONCE);

        //BPMN visualization persistence
        persistenceLayer.<Buffer, BasicExecution>registerPersistence("bpmnVisualization", (data, execution)->{
            ExternalArtifact bpmnArtifact = execution.processModelVisualizations().stream().filter(artifact->artifact.path().contains(BPMN_FILE_NAME)).findFirst().get();
            vertx.fileSystem().rxWriteFile(bpmnArtifact.path(), data).subscribe(()->log.info("bpmn visualization saved!"));
        }, PipelinePersistenceLayer.PersistenceType.ONCE);

        return persistenceLayer;
    }

    /**
     * Clear all indices associated with this pipeline
     * @param rc
     */
    @Override
    public void purgePipeline(RoutingContext rc) {
        elasticsearchService.deleteIndex(timelineIndex())
                .compose(mapper->elasticsearchService.deleteIndex(timelineEntityIndex()))
                .compose(mapper->elasticsearchService.deleteIndex(activityLabelIndex()))
                .onSuccess(done->rc.response().setStatusCode(200).end())
                .onFailure(err->{
                    log.error(err.getMessage(),err);
                    rc.response().setStatusCode(500).end();
                });
    }
}
