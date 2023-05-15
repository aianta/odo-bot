package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.PipelineService;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.exceptions.BadRequest;
import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.logpreprocessor.timeline.TimelineService;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.CompositeFuture;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

/**
 * Provide base/common/core fields, resources, and services to all pipelines.
 */
public abstract class AbstractPreprocessingPipeline implements PreprocessingPipeline, PipelineService {

    private static final Logger log = LoggerFactory.getLogger(AbstractPreprocessingPipeline.class);

    protected ElasticsearchService elasticsearchService;
    protected TimelineService timelineService;
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

    @Override
    public void executeHandler(RoutingContext rc) {

    }

    @Override
    public void timelinesHandler(RoutingContext rc) {
        try{
            final boolean isTransient = isTransient(rc);

            List<String> esIndices = rc.queryParam("index");

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

                //Pass the eventlogMap to the implementing subclass for processing.
                return makeTimelines(eventlogsMap);

            }).onSuccess(timelines->{
                JsonArray result = timelines.stream().map(Timeline::toJson).collect(JsonArray::new,JsonArray::add,JsonArray::addAll);
                rc.response().putHeader("Content-Type", "application/json").setStatusCode(200).end(result.encode());

                //If we weren't told to skip database operations for this segment of the pipeline
                if(!isTransient){
                    final String timelinesString = timelines.stream().map(Timeline::getId).collect(StringBuilder::new, (sb, ele)->sb.append(ele.toString() + ", "), StringBuilder::append).toString();

                    List<JsonObject> timelinesJson = timelines.stream().map(Timeline::toJson).collect(Collectors.toList());

                    //Save the timelines themselves
                    elasticsearchService.saveIntoIndex(timelinesJson, timelineIndex()).onSuccess(saved->{
                        log.info("Timelines {} persisted in elasticsearch index: {}", timelinesString,timelineIndex());
                    }).onFailure(err->{
                        log.error("Error persisting timelines {} into elasticsearch index: {}",timelinesString, timelineIndex());
                        log.error(err.getMessage(), err);
                    });

                    //Save the entities within the timeline in a separate index as well.

                    //Extract the entities from inside the result json arrays into a single List<JsonObject>
                    List<JsonObject> entities = timelinesJson.stream().map(timelineJson->
                            timelineJson.getJsonArray("data").stream()
                                    .map(o->(JsonObject)o)
                                    .collect(Collectors.toList())

                    ).collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);

                    final String entitiesString = entities.stream().map(entity->entity.getString("id")).collect(StringBuilder::new, (sb, id)->sb.append(id + ", "), StringBuilder::append).toString();
                    elasticsearchService.saveIntoIndex(entities, timelineEntityIndex()).onSuccess(saved->{
                        log.info("Entities {} persisted in elasticsearch index: {}",entitiesString,timelineEntityIndex());
                    }).onFailure(err->{
                        log.error("Error persisting entities {} into elastic search index: {}", entitiesString,timelineEntityIndex());
                    });

                }
            });


        } catch (BadRequest badRequest) {
            badRequest.printStackTrace();
            rc.response().setStatusCode(400).end(badRequest.getMessage());
        }


    }

    @Override
    public void timelineEntitiesHandler(RoutingContext rc) {

    }

    @Override
    public void activityLabelsHandler(RoutingContext rc) {

        try{
            final boolean isTransient = isTransient(rc);

            elasticsearchService.fetchAll(timelineEntityIndex())
                    .compose(entities->makeActivityLabels(entities))
                    .onSuccess(actvityLabels->{
                        rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(actvityLabels.encode());

                        //If we weren't told to skip database operations for this segment of the pipeline
                        if(!isTransient){
                            elasticsearchService.saveIntoIndex(List.of(actvityLabels), activityLabelIndex())
                                    .onSuccess(done->log.info("Persisted activity labels!"))
                                    .onFailure(err->log.error(err.getMessage(),err));
                        }

                    })
                    .onFailure(err->log.error(err.getMessage(), err));

        }catch (BadRequest badRequest){
            log.error(badRequest.getMessage());
            rc.response().setStatusCode(400).end(badRequest.getMessage());
        }



    }

    @Override
    public void xesHandler(RoutingContext rc) {
        CompositeFuture.all(
                elasticsearchService.fetchAll(activityLabelIndex()),
                elasticsearchService.fetchAll(timelineIndex())
        ).compose(input->{
            List<JsonObject> activityLabels = input.resultAt(0);
            List<JsonObject> timelines = input.resultAt(1);

            return makeXes(timelines.stream().collect(JsonArray::new,JsonArray::add, JsonArray::addAll), activityLabels.get(0));
        }).onSuccess(xesFile->{
            try{
                List<String> lines = Files.readAllLines(xesFile.toPath());
                StringBuilder builder = new StringBuilder();
                lines.forEach(line->builder.append(line + "\n"));
                rc.response().setStatusCode(200).putHeader("Content-Type", "text/xml").end(builder.toString());
            }catch (IOException e){
                log.error(e.getMessage(),e);
            }
        });


    }

    @Override
    public void processModelVisualizationHandler(RoutingContext rc) {
        log.info("Got here");
        try{
            File xesInput = new File("log.xes");

            boolean isTransient = isTransient(rc);

            makeModelVisualization(xesInput).onSuccess(
                    visualization->{

                        rc.response().setStatusCode(200).putHeader("Content-Type", "image/png").end(visualization);

                        if(!isTransient){ //If we're meant to persist artifacts from this segment of the pipeline do so now
                            vertx.fileSystem().rxWriteFile("bpmn.png", visualization).subscribe(()->log.info("visualization saved"));
                        }

                    }
            ).onFailure(err->{
                log.error(err.getMessage(),err);
                rc.response().setStatusCode(500).end(err.getMessage());
            });



        }catch (BadRequest badRequest){
            log.error(badRequest.getMessage(), badRequest);
            rc.response().setStatusCode(500).end(badRequest.getMessage());
        }



    }

    @Override
    public void processModelStatsHandler(RoutingContext rc) {

    }

    @Override
    public void processModelHandler(RoutingContext rc) {

    }

    protected void genericErrorHandler(Throwable e){
        log.error("Pipeline error!");
        log.error(e.getMessage(), e);
    }

    private boolean isTransient(RoutingContext rc) throws BadRequest {
        boolean isTransient = false; // Flag for whether to skip saving the resulting timeline in es.
        if(rc.request().params().contains("transient")){
            //Just some error handling
            if (rc.request().params().getAll("transient").size() > 1){
                rc.response().setStatusCode(400).end(new JsonObject().put("error", "cannot have multiple 'transient' parameters in request.").encode());
                throw new BadRequest("too many transient parameters. cannot have multiple 'transient' parameters in request.");
            }
            isTransient = Boolean.parseBoolean(rc.request().params().get("transient"));
        }

        return isTransient;
    }
}
