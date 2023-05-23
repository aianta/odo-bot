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
        execution.start();
        rc.put("metadata", execution);
        rc.next();
    }

    @Override
    public void executeHandler(RoutingContext rc) {

        //Create metadata record for the execution
        BasicExecution execution = new BasicExecution();
        execution.setId(UUID.randomUUID());
        execution.setPipelineId(id());
        execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.InProgress());
        execution.start();

        List<String> esIndices = rc.queryParam("index");

        /**
         *  Event logs should be returned with the oldest event first. Create a JsonArray
         *  following the format described here: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html
         *  to enforce this.
         */

        JsonArray sortOptions = new JsonArray()
                .add(new JsonObject().put(TIMESTAMP_FIELD, "asc"));

        CompositeFuture.all(
                esIndices.stream().map(index->elasticsearchService.fetchAndSortAll(index, sortOptions)).collect(Collectors.toList())
        ).compose(future->{
            List<List<JsonObject>> eventlogs = future.list();

            //Build a map associating the logs with their elasticsearch indices
            Map<String,List<JsonObject>> eventlogsMap = new HashMap<>();
            for(int i = 0; i < eventlogs.size(); i++){
                eventlogsMap.put(esIndices.get(i), eventlogs.get(i));
            }

            //Pass the eventlogMap to the implementing subclass for processing.
            return makeTimelines(eventlogsMap);
        }).compose( timelines -> {

            //Log the index-timelineId mappings into the execution record
            timelines.forEach(timeline->execution.registerTimeline(timeline.getAnnotations().getString("source-index"),timeline.getId()));

            JsonArray result = timelines.stream().map(Timeline::toJson).collect(JsonArray::new,JsonArray::add,JsonArray::addAll);

            final String timelinesString = timelines.stream().map(Timeline::getId).collect(StringBuilder::new, (sb, ele)->sb.append(ele.toString() + ", "), StringBuilder::append).toString();

            List<JsonObject> timelinesJson = timelines.stream().map(Timeline::toJson).collect(Collectors.toList());


            //Extract the entities from inside the result json arrays into a single List<JsonObject>
            List<JsonObject> entities = timelinesJson.stream().map(timelineJson->
                    timelineJson.getJsonArray("data").stream()
                            .map(o->(JsonObject)o)
                            .collect(Collectors.toList())

            ).collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);

            //Log all the entities that were used in this execution
            entities.forEach(entity->execution.timelineEntityIds().add(entity.getString("id")));

            log.info("About to make activities");
            return
                    makeActivityLabels(entities)
                    .compose(activityLabels->{
                        execution.setActivityLabelingId(UUID.fromString(activityLabels.getString("id")));
                        return makeXes(result, activityLabels);})
                            .compose(xesFile->{
                                execution.setXes(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, xesFile.toPath().toString()));
                                return makeModelVisualization(xesFile);
                            })
                            .onSuccess(visualization->{
                                rc.response().setStatusCode(200).putHeader("Content-Type", "image/png").end(visualization);

                                vertx.fileSystem().rxWriteFile("bpmn.png", visualization).subscribe(()->{
                                    log.info("visualization saved locally");
                                    execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, "bpmn.png"));
                                    execution.stop();
                                    execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.Complete(execution.status().data()));
                                    log.info("Execution: {}", execution.toJson().encodePrettily());
                                });

                            });
        }).onSuccess(done->log.info("done pipeline"));

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
                timelines.forEach(timeline->execution.registerTimeline(timeline.getAnnotations().getString("source-index"),timeline.getId()));
            }


            //Put timelines in routing context for further processing or persistence layer.
            rc.put("timelines", timelines);
            rc.next();
        });

    }

    @Override
    public void timelineEntitiesHandler(RoutingContext rc) {
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
        BasicExecution execution = rc.get("metadata");
        entityJson.forEach(entity->execution.timelineEntityIds().add(entity.getString("id")));

        //Put the extracted entities back into the routing context
        rc.put("entities", entityJson);
        rc.next();
    }

    @Override
    public void activityLabelsHandler(RoutingContext rc) {
        List<JsonObject> entities = rc.get("entities");

        makeActivityLabels(entities).onSuccess(activityLabels->{
                    //Update execution metadata
                    BasicExecution execution = rc.get("metadata");
                    execution.setActivityLabelingId(UUID.fromString(activityLabels.getString("id")));

                    rc.put("activities", activityLabels);
                    rc.next();
                })
                .onFailure(err->log.error(err.getMessage(), err));
    }

    @Override
    public void xesHandler(RoutingContext rc) {

        JsonObject activities = rc.get("activities");
        List<Timeline> timelines = rc.get("timelines");
        JsonArray timelinesJson = timelines.stream().map(Timeline::toJson).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        makeXes(timelinesJson, activities).onSuccess(xesFile->{
                //Update execution metadata
                BasicExecution execution = rc.get("metadata");
                execution.setXes(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, xesFile.toPath().toString()));
                rc.put("xesFile", xesFile);
                rc.next();
        });


    }

    @Override
    public void processModelVisualizationHandler(RoutingContext rc) {

        File xesInput = new File("log.xes");


        makeModelVisualization(xesInput).onSuccess(
                visualization->{
                    //Update execution metadata
                    BasicExecution execution = rc.get("metadata");
                    execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, "bpmn.png"));

                    rc.put("bpmnVisualization", visualization);
                    rc.next();

                }
        ).onFailure(err->{
            log.error(err.getMessage(),err);
            rc.response().setStatusCode(500).end(err.getMessage());
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
        persistenceLayer.<List<Timeline>>registerPersistence("timelines", (timelines)->{
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
        persistenceLayer.<List<JsonObject>>registerPersistence("entities", (entities)->{
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
        persistenceLayer.<BasicExecution>registerPersistence("metadata", (execution)->{
            elasticsearchService.updateExecution(execution)
                    .onSuccess(done->log.info("Execution record updated!"))
                    .onFailure(err->log.error(err.getMessage(),err))
            ;
        }, PipelinePersistenceLayer.PersistenceType.ALWAYS);

        //ActivityLabeling persistence
        persistenceLayer.<JsonObject>registerPersistence("activities", (activities)->{
            elasticsearchService.saveIntoIndex(List.of(activities), activityLabelIndex())
                    .onSuccess(done->log.info("Activity labels persisted!"))
                    .onFailure(err->log.error(err.getMessage(), err));
        }, PipelinePersistenceLayer.PersistenceType.ONCE);

        //BPMN visualization persistence
        persistenceLayer.<Buffer>registerPersistence("bpmnVisualization", (data)->{
            vertx.fileSystem().rxWriteFile("bpmn.png", data).subscribe(()->log.info("bpmn visualization saved!"));
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
