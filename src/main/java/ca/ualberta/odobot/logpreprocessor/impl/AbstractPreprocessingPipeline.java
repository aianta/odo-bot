package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.domsequencing.DOMSequencingService;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.PipelinePersistenceLayer;
import ca.ualberta.odobot.logpreprocessor.PipelineService;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.logpreprocessor.exceptions.BadRequest;
import ca.ualberta.odobot.logpreprocessor.executions.ExternalArtifact;
import ca.ualberta.odobot.logpreprocessor.executions.PreprocessingPipelineExecution;
import ca.ualberta.odobot.logpreprocessor.executions.impl.AbstractPreprocessingPipelineExecutionStatus;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;


import ca.ualberta.odobot.semanticflow.model.StateSample;
import ca.ualberta.odobot.semanticflow.model.Timeline;

import ca.ualberta.odobot.semanticflow.model.TrainingMaterials;
import ca.ualberta.odobot.semanticflow.model.semantictrace.SemanticTrace;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.CompositeFuture;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;

import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

/**
 * Provide base/common/core fields, resources, and services to all pipelines.
 */
public abstract class AbstractPreprocessingPipeline implements PreprocessingPipeline, PipelineService {

    private static final Logger log = LoggerFactory.getLogger(AbstractPreprocessingPipeline.class);

    protected ElasticsearchService elasticsearchService;
    protected DOMSequencingService domSequencingService;

    protected SqliteService sqliteService;

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
    protected PipelinePersistenceLayer persistenceLayer;

    protected Neo4JUtils neo4j;

    public AbstractPreprocessingPipeline(Vertx vertx, String slug){
        this.vertx = vertx;
        /**
         * Pipelines use proxies to interact with services allowing for better use of resources
         * across a distributed deployment.
         */
        domSequencingService = DOMSequencingService.createProxy(vertx.getDelegate(), DOMSEQUENCING_SERVICE_ADDRESS);

        //Need a custom service proxy builder here because elasticsearch queries can take a really long time.
        ServiceProxyBuilder esProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .setOptions(new DeliveryOptions().setSendTimeout(3600000)); //1hr timeout
        elasticsearchService = esProxyBuilder.build(ElasticsearchService.class);

        ServiceProxyBuilder dbProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(SQLITE_SERVICE_ADDRESS);
        dbProxyBuilder.setOptions(new DeliveryOptions().setSendTimeout(3600000)); //1hr timeout

        sqliteService = dbProxyBuilder.build(SqliteService.class);


        client = WebClient.create(vertx);

        neo4j = new Neo4JUtils("bolt://localhost:7687", "neo4j", "odobotdb");

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

    public abstract Future<JsonObject> makeActivityLabels(List<JsonObject> entities);

    public void afterExecution(RoutingContext rc){

        BasicExecution execution = rc.get("metadata");

        /**
         * Look up all activity labels and print out JSON files with underlying events for each label
         */
        List<JsonObject> entities = rc.get("entities");
        JsonObject mappings = ((JsonObject)rc.get("activities")).getJsonObject("mappings");
        Set<String> uniqueActivityLabels = mappings.stream()
                .map(entry->entry.getValue())
                .map(value->(String)value)
                .collect(Collectors.toSet());

        for (String label: uniqueActivityLabels){

            Set<String> entityIdsWithSpecifiedLabel = new HashSet<>();
            mappings.forEach(entry->{
                if(((String)entry.getValue()).equals(label)){
                    entityIdsWithSpecifiedLabel.add(entry.getKey());
                }
            });

            JsonArray clusteredEntities = entities.stream().filter(e->entityIdsWithSpecifiedLabel.contains(e.getString("id"))).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
            String artifactName = "underlying_" + label + ".json";
            rc.put(artifactName, clusteredEntities);

            execution.clusteringResults().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, Path.of(execution.dataPath(), artifactName).toString()));
            persistenceLayer.<JsonArray, BasicExecution>registerPersistence(artifactName, (jsonArray, exec)->{
                ExternalArtifact artifact = exec.clusteringResults().stream().filter(a->a.path().endsWith(artifactName)).findFirst().orElseThrow(()->new RuntimeException(("Couldn't find clustering result " + artifactName + " to persist!")));
                vertx.fileSystem().rxWriteFile(artifact.path(), Buffer.buffer(jsonArray.encode())).subscribe(()->log.info("{} saved! ", artifactName ));
            }, PipelinePersistenceLayer.PersistenceType.ONCE);

        }

        /**
         * Update the execution metadata
         */

        execution.stop();
        execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.Complete(execution.status().data()));
        rc.next();
    }

    public void beforeExecution(RoutingContext rc){
        //Create metadata record for the execution
        BasicExecution execution = new BasicExecution();
        execution.setId(UUID.randomUUID());
        execution.setPipelineClass(getClass().getName());
        execution.setPipelineId(id());
        execution.setStatus(new AbstractPreprocessingPipelineExecutionStatus.InProgress());
        execution.status().data().put("step", "beforeExecution"); //Update execution step
        execution.start();
        rc.put("metadata", execution);
        rc.next();
    }

    public void navModelHandler(RoutingContext rc){

        //Update bookkeeping for this execution
        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "extractStateSamplesHandler");
        }

        List<Timeline> timelines = rc.get("timelines");

        log.info("Constructing model for timelines");

        Future<Void> f = null;

        Iterator<Timeline> it = timelines.iterator();
        while (it.hasNext()){
            Timeline curr = it.next();

            if(f == null){
                f = vertx.getDelegate().<Void>executeBlocking(blocking->buildNavModel(curr)
                        .onSuccess(done->blocking.complete(done))
                        .onFailure(err->blocking.fail(err))
                );
            }else{
                f = f.compose(
                        (done)->vertx.getDelegate().<Void>executeBlocking(blocking->buildNavModel(curr)
                                        .onSuccess(d->blocking.complete(d))
                                .onFailure(err->blocking.fail(err))
                        ));
            }
        }

        f
                .onSuccess(d->log.info("Successfully finished adding timelines to model"))
                .onFailure(err->log.error(err.getMessage(), err))
                .onComplete(r->{
            log.info("Done constructing model");
            rc.next();
        });

//        List<Future<Void>> processing = timelines.stream().map(timeline->{
//            return vertx.getDelegate().<Void>executeBlocking(blocking->buildNavModel(timeline)
//                    .onSuccess(done->blocking.complete(done))
//                    .onFailure(err->blocking.fail(err))
//            );
//        }).collect(Collectors.toList());
//
//        Future.all(processing)
//                .onSuccess(done->{
//                    rc.next();
//                })
//                .onFailure(err->log.error(err.getMessage(),err))
//        ;

    }

    public void chunkedSemanticTracesHandler(RoutingContext rc){

        String flightIdentifierField = rc.request().getParam("flight_identifier_field", "flight_name.keyword");
        rc.put("flightIdentifierField", flightIdentifierField);

        String index = rc.request().getParam("index");
        rc.put("index", index);

        int chunkSize = Integer.parseInt(rc.queryParam("chunkSize").get(0));

        String dataset = rc.request().getParam("dataset");

        if(dataset != null){
            rc.put("dataset", dataset);
        }

        rc.put("chunkSize", chunkSize);

        elasticsearchService.getFlights(index, flightIdentifierField)
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(flightSet->{

                    //Now let's go check to see how much progress has been made
                    sqliteService.getHarvestProgress(rc.get("dataset"))
                            .onSuccess(progress->{

                                //Every index that appears in the progress set has already been harvested, so no need to include them again.

                                List<String> todo = new ArrayList<>();

                                todo.addAll(flightSet);

                                int originalTodoSize = todo.size();
                                log.info("{} indices toDo...", originalTodoSize);
                                List<String> finalTodo = todo.stream().filter(flight->!progress.contains(flight)).collect(Collectors.toList());
                                log.info("skipping {} flights that are already complete...", originalTodoSize - finalTodo.size() );

                                rc.put("todo", finalTodo);
                                log.info("Flights todo: {}", finalTodo.toString());
                                log.info("In {} sized chunks", chunkSize);

                                rc.next();
                            })
                            .onFailure(err->{
                                log.error(err.getMessage(), err);
                            })
                    ;


                });


    }

    @Override
    public void timelinesHandler(RoutingContext rc) {

        if(rc.get("todo") != null){
            List<String> todo = rc.get("todo");
            List<String> flights = new ArrayList<>();

            Iterator<String> it = todo.iterator();
            int counter = rc.get("chunkSize");
            while (it.hasNext() && counter > 0){
                flights.add(it.next());
                it.remove();
                counter--;
            }

            rc.put("todo", todo);
            rc.put("flights", flights);
        }else{

            String identifierField = rc.request().getParam("flight_identifier_field", "flightID.keyword");

            rc.put("flightIdentifierField", identifierField );

            List<String> flights = rc.queryParam("flight");
            rc.put("flights", flights);

            String index = rc.request().getParam("index");
            rc.put("index", index);
        }



        /**
         *  Event logs should be returned with the oldest event first. Create a JsonArray
         *  following the format described here: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html
         *  to enforce this.
         */
        JsonArray sortOptions = new JsonArray()
                .add(new JsonObject().put(TIMESTAMP_FIELD, "asc"));

        List<String> flights = rc.get("flights");
        String flightIdentifierField = rc.get("flightIdentifierField");

        //Fetch the event logs corresponding with every flight requested
        CompositeFuture.all(
                flights.stream().map(flight->elasticsearchService.fetchFlightEvents(rc.get("index"), flight, flightIdentifierField, sortOptions)).collect(Collectors.toList())
        ).onFailure(err->log.error(err.getMessage(), err))
        .compose(future->{
            List<List<JsonObject>> eventlogs = future.list();

            //Build a map associating the logs with their flights
            Map<String,List<JsonObject>> eventlogsMap = new HashMap<>();

            /**
             *
             * A list of specific flights are given. Each will be retrieved individually
             * and each List<JsonObject> in the eventlogs variable corresponds with the documents/events for one of the
             * given flights.
             *
             * Each JsonObject will include a field 'flightName', which specifies the flight
             * from which it originated. We use this field to sort out where each event belongs.
             */

            //Go through every eventlog returned to us by the elastic search service.
            Iterator<List<JsonObject>> eventLogIt = eventlogs.iterator();
            while (eventLogIt.hasNext()){
                List<JsonObject> eventlog = eventLogIt.next();

                //Go through every event in a given event log
                Iterator<JsonObject> eventIt = eventlog.iterator();
                while (eventIt.hasNext()){

                    /**
                     * For each event, check its 'flightName' field to place it into the correct map entry. See {@link ca.ualberta.odobot.elasticsearch.impl.FetchAllTask#fetch(BiFunction)}
                     */

                    JsonObject event = eventIt.next();
                    List<JsonObject> targetList = eventlogsMap.getOrDefault(event.getString("flightName"), new ArrayList<>());
                    targetList.add(event);
                    eventlogsMap.put(event.getString("flightName"), targetList);

                }

            }

            log.info("Retrieved data from the following flights:");
            //List out the indices retrieved for sanity purposes.
            eventlogsMap.keySet().forEach(index->log.info("{}", index));

            rc.put("rawEventsMap", eventlogsMap);

            //Construct timelines for each index on separate threads.
            List<Future<Timeline>> timelineFutures = new ArrayList<>();
            eventlogsMap.forEach((flight, events)->{
                timelineFutures.add(
                vertx.getDelegate().executeBlocking(blocking->{
                    //Pass the sourceIndex, and timeline events to the implementing subclass for processing.
                    makeTimeline(flight, events)
                            .onSuccess(timeline->blocking.complete(timeline))
                            .onFailure(err->blocking.fail(err));
                },false));
            });

            eventlogs = null; //Explicitly free for garbage collection

            return Future.all(timelineFutures)
                    .onFailure(err->{
                        log.error("Error while constructing timelines!");
                        log.error(err.getMessage(), err);
                    })
                    .compose(result-> Future.succeededFuture(result.<Timeline>list()));



            //Pass the eventlogMap to the implementing subclass for processing.
//            return makeTimelines(eventlogsMap);

        }).onFailure(err->{
            log.error(err.getMessage(), err);
                })
                .onSuccess(timelines->{
            log.info("Got Timelines!");
            BasicExecution execution = rc.get("metadata");
            //Update execution metadata
            if(execution != null){
                execution.status().data().put("step", "timelinesHandler");
                timelines.forEach(timeline->execution.registerTimeline(timeline.getAnnotations().getString("flight-name"),timeline.getId()));
            }

            log.info("Saving {} timelines to routing context", timelines.size());
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

    public void makeTrainingExemplarsHandler(RoutingContext rc){
        //Update bookkeeping for this execution
        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "makeTrainingExemplarsHandler");
        }

        List<TrainingMaterials> materials = rc.get("trainingMaterials");
        makeTrainingExemplars(materials).onSuccess(exemplars->{
            rc.put("trainingExemplars", exemplars);

            rc.next();
        });

    }

    public void extractStateSamplesHandler(RoutingContext rc){
        //Update bookkeeping for this execution
        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "extractStateSamplesHandler");
        }

        //Get the name of the training dataset we're extracting state samples for if it exists
        String datasetName = rc.request().getParam("dataset") == null?rc.get("dataset"):rc.request().getParam("dataset");
        if(datasetName == null){
            datasetName = "default"; //Add to the default dataset if no dataset is otherwise specified.
        }

        //Get state samples from every timeline
        List<Timeline> timelines = rc.get("timelines");

        log.info("Gathering state samples from {} timelines", timelines.size());

        String finalDatasetName = datasetName;
        List<Future<List<StateSample>>> materials = timelines.stream().map(timeline->{
            return vertx.getDelegate().<List<StateSample>>executeBlocking(blocking->{
                extractStateSamples(timeline, finalDatasetName)
                        .onSuccess(done->{
                            blocking.complete(done);
                        })
                        .onFailure(err->blocking.fail(err));
            });
        }).collect(Collectors.toList());


        //Collect the state samples harvested from every timeline into a single materials list
        Future.all(materials).onSuccess(data->{
            List<StateSample> dataset = new ArrayList<>();
            data.<List<StateSample>>list().forEach(materialsList->dataset.addAll(materialsList));
            log.info("{} state samples found!", dataset.size());

            dataset.forEach(sample->sqliteService.saveStateSample(sample.toJson()));

            rc.next();
        });

    }

    public void captureTrainingMaterialsHandler(RoutingContext rc){

        //Update bookkeeping for this execution
        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "captureTrainingMaterialsHandler");
        }

        //Get the name of the training dataset we're capturing materials for if it exists

        String datasetName = rc.request().getParam("dataset") == null?rc.get("dataset"):rc.request().getParam("dataset");
        if(datasetName == null){
            datasetName = "default"; //Add to the default dataset if no dataset is otherwise specified.
        }

        //Get training materials from every timeline
        List<Timeline> timelines = rc.get("timelines");

        log.info("Gathering training materials from {} timelines", timelines.size());

        String finalDatasetName = datasetName;
        List<Future<List<TrainingMaterials>>> materials = timelines.stream().map(timeline->{
            return vertx.getDelegate().<List<TrainingMaterials>>executeBlocking(blocking->{
                captureTrainingMaterials(timeline, finalDatasetName)
                        .onSuccess(done->{
                            blocking.complete(done);
                        })
                        .onFailure(err->blocking.fail(err));
            });
        }).collect(Collectors.toList());


        //Collect the materials harvested from every timeline into a single materials list
        Future.all(materials).onSuccess(data->{
            List<TrainingMaterials> dataset = new ArrayList<>();
            data.<List<TrainingMaterials>>list().forEach(materialsList->dataset.addAll(materialsList));
            log.info("{} training materials found!", dataset.size());

            dataset.forEach(material->sqliteService.saveTrainingMaterial(material.toJson()));

            //During chunked requests, there may already be training materials from previous timelines, if so, we want to add to them.
            List<TrainingMaterials> existingMaterials = rc.get("trainingMaterials");
            if(existingMaterials != null){
                existingMaterials.addAll(dataset);
                rc.put("trainingMaterials", existingMaterials);
            }else{
                rc.put("trainingMaterials", dataset);
            }
            rc.next();
        });

    }

    public void semanticTraceHandler(RoutingContext rc){
        //Update bookkeeping for this execution
        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "semanticLabelingHandler");
        }

        //Get the timelines to process
        List<Timeline> timelines = rc.get("timelines");

        //Make semantic labels for each timeline in a separate thread.
        List<Future> timelineFutures = timelines.stream()
                .map(timeline->{
                    return vertx.getDelegate().<SemanticTrace>executeBlocking(blocking->{
                        makeSemanticTrace(timeline)
                                .onSuccess(result->blocking.complete(result))
                                .onFailure(err->blocking.fail(err));
                    }, false);
                })
                .collect(Collectors.toList());

        //Wait for all timelines to finish processing
        CompositeFuture.all(timelineFutures)
                .onSuccess(results->{
                    //Then put the processed, updated timelines in the routing context and call the next handler.
                    List<SemanticTrace> semanticTraces = results.<SemanticTrace>list();
                    rc.put("semanticTraces", semanticTraces);

                    log.info("{} semantic traces", semanticTraces.size());

                    rc.next();
                });
    }

    @Override
    public void activityLabelsHandler(RoutingContext rc) {
        List<JsonObject> entities = rc.get("entities");

        BasicExecution execution = rc.get("metadata");
        if(execution != null){
            execution.status().data().put("step", "activityLabelsHandler");
        }

        //Filter out network events as they are fixed points, and thus don't require embedding/clustering.
        entities = entities.stream().filter(entity->!entity.getString("symbol").equals("NET")).collect(Collectors.toList());

        makeActivityLabels(entities).onSuccess(activityLabels->{
                    //Update execution metadata
                    if(execution != null){
                        execution.setActivityLabelingId(UUID.fromString(activityLabels.getString("id")));

                        //Extract clustering results figures for each type of event, and put them in the routing context for persistence
                        activityLabels.forEach(entry->{
                            if(entry.getKey().startsWith(CLUSTERING_RESULTS_FIELD_PREFIX)){
                                rc.put(entry.getKey(), Buffer.buffer(Base64.getDecoder().decode((String)entry.getValue())));
                                execution.clusteringResults().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, Path.of(execution.dataPath(), entry.getKey() + ".png").toString()));
                                persistenceLayer.<Buffer, BasicExecution>registerPersistence(entry.getKey(), (buffer, exec)->{
                                    ExternalArtifact artifact = exec.clusteringResults().stream().filter(a->a.path().contains(entry.getKey())).findFirst().orElseThrow(()->new RuntimeException("Couldn't find clustring result " + entry.getKey() + " to persist"));
                                    vertx.fileSystem().rxWriteFile(artifact.path(), buffer).subscribe(()->log.info("{} clustering saved!", artifact.path()));
                                }, PipelinePersistenceLayer.PersistenceType.ONCE );
                            }
                        });

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
                visualizations->{


                    //Update execution metadata
                    if (execution != null){
                        if (visualizations.containsKey(BPMN_KEY)){
                            var bpmnPath = Path.of(execution.dataPath(), BPMN_FILE_NAME);
                            execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, bpmnPath.toString()));
                        }


                        var treePath = Path.of(execution.dataPath(), TREE_FILE_NAME);
                        var dfgPath = Path.of(execution.dataPath(), DFG_FILE_NAME);
                        var petriPath = Path.of(execution.dataPath(), PETRI_FILE_NAME);
                        var transitionPath = Path.of(execution.dataPath(), TRANSITION_FILE_NAME);


                        execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, treePath.toString()));
                        execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, dfgPath.toString()));
                        execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, petriPath.toString()));
                        execution.processModelVisualizations().add(new ExternalArtifact(ExternalArtifact.Location.LOCAL_FILE_SYSTEM, transitionPath.toString()));
                    }

                    //Pack visualization data into routing context
                    visualizations.forEach((key, buffer)->rc.put(key, buffer));

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
        if(persistenceLayer != null){
            return  persistenceLayer;
        }

        PipelinePersistenceLayer persistenceLayer = new PipelinePersistenceLayer();
        this.persistenceLayer = persistenceLayer;

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

        //Visualization persistence
        persistenceLayer.registerPersistence(BPMN_KEY, new VisualizationPersistence(vertx, BPMN_FILE_NAME), PipelinePersistenceLayer.PersistenceType.ONCE);
        persistenceLayer.registerPersistence(TREE_KEY, new VisualizationPersistence(vertx, TREE_FILE_NAME), PipelinePersistenceLayer.PersistenceType.ONCE);
        persistenceLayer.registerPersistence(DFG_KEY, new VisualizationPersistence(vertx, DFG_FILE_NAME), PipelinePersistenceLayer.PersistenceType.ONCE);
        persistenceLayer.registerPersistence(PETRI_KEY, new VisualizationPersistence(vertx, PETRI_FILE_NAME), PipelinePersistenceLayer.PersistenceType.ONCE);
        persistenceLayer.registerPersistence(TRANSITION_KEY, new VisualizationPersistence(vertx,TRANSITION_FILE_NAME), PipelinePersistenceLayer.PersistenceType.ONCE);


        return persistenceLayer;
    }

    /**
     * Helper class for persisting process model visualizations
     */
    private static class VisualizationPersistence implements BiConsumer<Buffer, BasicExecution> {

        String fileName;
        Vertx vertx;

        public VisualizationPersistence(Vertx vertx,String artifactKeyword){
            this.fileName = artifactKeyword;
            this.vertx = vertx;
        }

        @Override
        public void accept(Buffer buffer, BasicExecution execution) {
            ExternalArtifact artifact = execution.processModelVisualizations().stream().filter(a->a.path().contains(fileName)).findFirst().orElseThrow(()-> new RuntimeException("Could not find artifact with fileName: " + fileName));
            vertx.fileSystem().rxWriteFile(artifact.path(), buffer).subscribe(()->log.info("{} visualization saved!", fileName));
        }
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

    protected Future<JsonObject> callActivityLabelEndpoint(String endpoint, List<JsonObject> entities){

        Promise<JsonObject> promise = Promise.promise();

        JsonArray entitiesJson = entities.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        JsonObject requestObject = new JsonObject()
                .put("id", UUID.randomUUID().toString()).put("entities", entitiesJson);

//        log.info("requestObject: {}", requestObject.encodePrettily());

        client.post(DEEP_SERVICE_PORT, DEEP_SERVICE_HOST, endpoint )
                .rxSendJsonObject(requestObject)
                .doOnError(err->{
                    promise.fail(err);
                    genericErrorHandler(err);
                }).subscribe(response->{
                    JsonObject data = response.bodyAsJsonObject();
                    log.info("{}", data.encodePrettily());
                    promise.complete(data);
                });

        return promise.future();
    }
}
