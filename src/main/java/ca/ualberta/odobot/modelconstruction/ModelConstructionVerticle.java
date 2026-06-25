package ca.ualberta.odobot.modelconstruction;

import ca.ualberta.odobot.common.*;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.mind2web.HTMLCleaningTools;
import ca.ualberta.odobot.modelconstruction.eventlabeling.EventDescription;
import ca.ualberta.odobot.modelconstruction.eventlabeling.LabelTrajectoryTask;
import ca.ualberta.odobot.modelconstruction.impl.TagAndAttributeStrategy;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;

import ca.ualberta.odobot.modelconstruction.impl.visitors.BlankRemovingVisitor;
import ca.ualberta.odobot.modelconstruction.impl.visitors.XpathSnapshotVisitor;
import ca.ualberta.odobot.modelconstruction.linklabeling.LinkLabelingService;
import ca.ualberta.odobot.modelconstruction.statelabeling.StateLabelingService;
import ca.ualberta.odobot.semanticflow.model.NetworkEvent;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.SqliteVectorService;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import ca.ualberta.odobot.taskplanner.TaskPlannerVerticle;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import io.vertx.rxjava3.ext.web.RoutingContext;

import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.common.Predicates.stateClusteringEventFilter;
import static ca.ualberta.odobot.logpreprocessor.Constants.*;
import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

/**
 * Exposes HTML cleaning functionality.
 */
public class ModelConstructionVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ModelConstructionVerticle.class);

    @Override
    public String serviceName() {
        return "ModelConstructionService";
    }

    @Override
    public String configFilePath() {
        return "config/model-construction.yaml";
    }

    public static SqliteService sqliteService;
    public static ElasticsearchService elasticsearchService;
    public static StateLabelingService stateLabelingService;
    public static LinkLabelingService linkLabelingService;
    public static TaskPlannerService taskPlannerService;



    private ExecutorService executorService = Executors.newFixedThreadPool(6);

    private static String ODO_LSH_HOST = "172.26.130.231";
    private int ODO_LSH_PORT = 5000;

    private WebClient webClient;

    private CleanerService cleanerService;
    private RobulaPlus robulaPlus = new RobulaPlus();

    private SqliteVectorService sqliteVectorService;

    @Override
    public Completable onStart() {
        super.onStart().subscribe();

        //Override odo LSH host from config file.
        ODO_LSH_HOST = _config.getString("odoLshHost");
        ODO_LSH_PORT = Integer.parseInt(_config.getString("odoLshPort"));

        WebClientOptions webClientOptions = new WebClientOptions()
                .setUserAgent("OdoBot");
        webClient = WebClient.create(vertx.getDelegate(), webClientOptions);

        //Init SQLiteVectorService
        sqliteVectorService = SqliteVectorService.create(_config.getJsonObject("sqliteVectorConfig"));
        new ServiceBinder(vertx.getDelegate())
                .setAddress(SQLITE_VECTOR_SERVICE_ADDRESS)
                .register(SqliteVectorService.class, sqliteVectorService)
        ;


        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        ServiceProxyBuilder taskPlanningServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(TASK_PLANNER_SERVICE_ADDRESS);
        taskPlanningServiceProxyBuilder.setOptions(new DeliveryOptions().setSendTimeout(3600000)); //1hr timeout

        taskPlannerService = taskPlanningServiceProxyBuilder.build(TaskPlannerService.class);

        //Init Link Labeling service
        linkLabelingService = LinkLabelingService.create(_config);

        //Init ElasticSearch Service Proxy
        elasticsearchService =  new ServiceProxyBuilder(vertx.getDelegate())
                .setOptions(new DeliveryOptions().setSendTimeout(300000))
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .build(ElasticsearchService.class);

        cleanerService = CleanerService.create(vertx.getDelegate(), _config, new TagAndAttributeStrategy(vertx.getDelegate()));

        stateLabelingService = StateLabelingService.create(vertx.getDelegate(), _config);


        api.route().method(HttpMethod.DELETE).path("/tokenUsage").handler(rc->{
            AbstractOpenAIStrategy.activeTokenUsageRecord = new TokenUsageRecord();
            rc.getDelegate().response().setStatusCode(200).end(AbstractOpenAIStrategy.activeTokenUsageRecord.toJson().encodePrettily());
        });
        api.route().method(HttpMethod.GET).path("/tokenUsage").handler(rc->{
            rc.getDelegate().response().setStatusCode(200).end(AbstractOpenAIStrategy.activeTokenUsageRecord.toJson().encodePrettily());
        });

        api.route().method(HttpMethod.GET).path("/sampleDOMSnapshot").handler(this::sampleDOMSnapshot);
        api.route().method(HttpMethod.POST).path("/clean").handler(this::clean);
        api.route().method(HttpMethod.POST).path("/nodeLinks").handler(this::nodeLinks);
        api.route().method(HttpMethod.GET).path("/buildStateClusters").handler(this::buildStateClusters);
        api.route().method(HttpMethod.GET).path("/mineCommonSubstructures").handler(this::mineCommonDOMSubstructures);


        //Not sure if this handler's logic still holds after numShingles changes...
        api.route().method(HttpMethod.GET).path("/computeAnnotations").handler(this::computeAnnotations);

        api.route().method(HttpMethod.GET).path("/loadDOMSnapshots").handler(this::loadDOMSnapshots);
        api.route().method(HttpMethod.GET).path("/processHrefs").handler(this::processHrefs);
        api.route().method(HttpMethod.GET).path("/detectDxpaths").handler(this::detectDxpaths);
        api.route().method(HttpMethod.GET).path("/resolveClusteredNodes")
                .handler(this::mineCommonDOMSubstructures)
                .handler(this::getNodeClustering)
                .handler(this::getNodeClusteringDocument)
                .handler(this::resolveClusterNodesV2)
        ;
        api.route().method(HttpMethod.GET).path("/labelStateClusters")
                .handler(this::buildStateClusters)
                .handler(this::getClustering)
                .handler(this::getClusterSnapshots);

        //Batch describe trajectories at scale.
        api.route().method(HttpMethod.GET).path("/describeTrajectories")
                .handler(rc->LogPreprocessor.minimalPipeline.chunkedSemanticTracesHandler(rc))
                .handler(rc->rc.reroute(_config.getString("apiPathPrefix").substring(0, _config.getString("apiPathPrefix").length() - 2) + "/describeTrajectory"));

        api.route().method(HttpMethod.GET).path("/describeTrajectory")
                .handler(rc->LogPreprocessor.minimalPipeline.timelinesHandler(rc))
                .handler(this::describeTrajectory)
                .handler(rc->{
                    if(rc.get("todo") != null && ((List<String>)rc.get("todo")).size()> 0){
                        rc.reroute(HttpMethod.GET, _config.getString("apiPathPrefix").substring(0,_config.getString("apiPathPrefix").length()-2) + "/describeTrajectory");
                    }else{
                        rc.response().setStatusCode(200).end("done");
                    }
                })
        ;

        api.route().method(HttpMethod.GET).path("/embedTrajectories")
                .handler(this::embedTrajectories);

        api.route().method(HttpMethod.GET).path("/queryTrajectories")
                .handler(this::queryTrajectories);

        api.route().method(HttpMethod.GET).path("/describeModel")
                .handler(this::describeModel);

        return Completable.complete();

    }

    private void describeModel(RoutingContext rc){

        Set<String> symbolsToInclude = Set.of("CE", "S", "DE", "CHKBX", "RAD");

        Future.all(
                        symbolsToInclude.stream()
                                .map(nodeType->{
                                    return sqliteService.getModelNodeIdsBySymbol(nodeType).compose(
                                            nodeIds->{

                                                return Future.all(
                                                        nodeIds.stream()
                                                                .map(nodeId->sqliteService.getEventDescriptionsForNodeId(nodeId)
                                                                        .compose(descriptions->taskPlannerService.generateNodeAnnotation(descriptions.stream().map(json->json.getString("description")).toList()))
                                                                        .compose(annotation->{
                                                                            log.info("Annotation for {} is: \n{}", nodeId, annotation);
                                                                            TaskPlannerVerticle.neo4j.saveAnnotation(nodeId, annotation);
                                                                            return Future.succeededFuture(new JsonObject().put(nodeId, annotation));
                                                                        })
                                                                ).toList()

                                                );
                                            }
                                    );

                                }).toList()
        ).onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(done->{


                    JsonObject result = new JsonObject();
                    for (CompositeFuture f: done.<CompositeFuture>list()){
                        List<JsonObject> annotations = f.<JsonObject>list();
                        annotations.forEach(result::mergeIn);
                    }

                    rc.getDelegate().response().setStatusCode(200).end(result.encode());
                });
        ;

        ;





    }

    private void queryTrajectories(RoutingContext rc){
        int k = Integer.parseInt(rc.request().getParam("k", "5"));
        JsonObject request = rc.body().asJsonObject();
        JsonArray tasks = request.getJsonArray("tasks");



        sqliteService.getSyntheticTasks()
                        //Get all the synthetic tasks, we'll use these to populate the information about matched tasks. We need this because sqliteVectorService.topK only returns trajectory ids and distances.
                        .compose(syntheticTasks->{


                            List<Future<CompositeFuture>> taskQueries = tasks.stream()
                                    .map(JsonObject.class::cast)
                                    .map(queryTask->{
                                        return Future.all(
                                                Future.succeededFuture(queryTask),
                                                taskPlannerService.rewriteQueryTaskWithoutSpecificInputs(queryTask.getJsonObject("odoBotNL").getString("task"), syntheticTasks)
                                        );
                                    })

                                    .map(compositeFuture->{

                                        return compositeFuture.compose(results->{

                                            JsonObject queryTask = results.resultAt(0);
                                            String rewrittenTask = results.resultAt(1);

                                            log.info("Rewrote task:\n{}\nto:\n{}", queryTask.getJsonObject("odoBotNL").getString("task"), rewrittenTask);
                                            return Future.all(
                                                    Future.succeededFuture(queryTask.getJsonObject("odoBotNL").put("rewrittenTo", rewrittenTask)),
                                                    sqliteVectorService.topK(k, rewrittenTask).compose(
                                                            hits-> {
                                                                List<JsonObject> matchedTasks = syntheticTasks.stream()
                                                                        .filter(task->hits.stream().map(json->json.getString("trajectoryId")).toList().contains(task.getString("id"))).toList();

                                                                //Merge in query distance
                                                                matchedTasks.stream().forEach(task->{
                                                                    task.put("distance",hits.stream().filter(hit->hit.getString("trajectoryId").equals(task.getString("id"))).findFirst().get().getFloat("distance"));;
                                                                });

                                                                return Future.all(
                                                                        matchedTasks.stream()
                                                                                .map(matchedTask->sqliteService.getAPICallsForTrajectory(matchedTask.getString("id"))
                                                                                        .compose(apiCalls->{
                                                                                            JsonObject resultEntry = new JsonObject();
                                                                                            resultEntry.mergeIn(matchedTask);
                                                                                            resultEntry.put("apiCalls", apiCalls.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

                                                                                            return Future.succeededFuture(resultEntry);
                                                                                        })
                                                                                )
                                                                                .toList()
                                                                ).compose(queryResults->{
                                                                    List<JsonObject> _queryResults = queryResults.list();
                                                                    _queryResults.sort(Comparator.comparingDouble(json->json.getFloat("distance")));
                                                                    return Future.succeededFuture(_queryResults.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
                                                                });
                                                            }
                                                    )
                                            );

                                                }
                                        );




                                            })
                                    .toList();

                            return Future.all(taskQueries)
                                    .compose(_results->{
                                        List<CompositeFuture> results = _results.list();

                                        return Future.all(results.stream()
                                                .map(result->{

                                                    JsonObject query = result.resultAt(0);
                                                    JsonArray options = result.resultAt(1);
                                                    return taskPlannerService.pickMostRelevantTask(query.getString("task"), options.stream().map(JsonObject.class::cast).toList())
                                                                    .compose(mostRelevantTask->{
                                                                        JsonObject queryResult = new JsonObject();
                                                                        queryResult.put("query", query)
                                                                                .put("results", options)
                                                                                .put("mostRelevant", mostRelevantTask)
                                                                        ;
                                                                        return Future.succeededFuture(queryResult);
                                                                    });


                                                }).toList());
                                    }).compose(_results->{
                                        return Future.succeededFuture(_results.list()
                                                        .stream()
                                                                .map(JsonObject.class::cast)
                                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
                                    });


                        })
                .onFailure(err->{
                    log.error(err.getMessage(),err);
                    rc.getDelegate().response().setStatusCode(500).end(err.getMessage());
                })
                .onSuccess(response->rc.getDelegate().response().setStatusCode(200).end(response.encode()));
        ;

//        Future.all(
//                taskHits
//        ).onFailure(err->log.error(err.getMessage(), err))
//                .compose(results->{
//
//                    List<JsonObject> syntheticTasks = results.resultAt(0);
//                    List<JsonObject> hits = results.resultAt(1);
//
//                    List<JsonObject> matchedTasks = syntheticTasks.stream()
//                            .filter(task->hits.stream().map(json->json.getString("trajectoryId")).toList().contains(task.getString("id"))).toList();
//
//                    //Merge in query distance
//                    matchedTasks.stream().forEach(task->{
//                        task.put("distance",hits.stream().filter(hit->hit.getString("trajectoryId").equals(task.getString("id"))).findFirst().get().getFloat("distance"));;
//                    });
//
//                    return Future.all(
//                            matchedTasks.stream()
//                                    .map(matchedTask->sqliteService.getAPICallsForTrajectory(matchedTask.getString("id"))
//                                            .compose(apiCalls->{
//                                                JsonObject resultEntry = new JsonObject();
//                                                resultEntry.mergeIn(matchedTask);
//                                                resultEntry.put("apiCalls", apiCalls.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
//
//                                                return Future.succeededFuture(resultEntry);
//                                            })
//                                    )
//                                    .toList()
//                    );
//                }).onSuccess(queryResults->{
//                    List<JsonObject> _queryResults = queryResults.list();
//                    _queryResults.sort(Comparator.comparingDouble(json->json.getFloat("distance")));
//
//                    rc.getDelegate().response().setStatusCode(200).end(_queryResults.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode());
//                });
    }


    private void embedTrajectories(RoutingContext rc){

        Future.all(
                sqliteService.getTrajectoryIdsFromSyntheticTaskVectorsTable(),
                sqliteService.getSyntheticTasks()
        ).onFailure(err->log.error(err.getMessage(), err))
                .compose(state->{
                    Set<String> alreadyEmbeddedTrajectoryIds = state.resultAt(0);
                    List<JsonObject> tasks = state.resultAt(1);

                    return Future.all(
                            tasks.stream()
                                    //Only embed trajectories that don't already have an entry in the synthetic task vectors table.
                                    .filter(task->!alreadyEmbeddedTrajectoryIds.contains(task.getString("id")))
                                    .map(task->sqliteVectorService.embedSyntheticTask(task.getString("id"), task.getString("task"))
                                    ).collect(Collectors.toList())
                    );
                }).onSuccess(done->{
                    //After all embeddings have been computed and stored in the vector table, prepare the vectors for querying.
                    //This initalizes the vectors, performs quantization and loaded the quantized vectors into memory for fast retrieval.
                    sqliteVectorService.readyVectorsForQuerying();
                    rc.getDelegate().response().setStatusCode(200).end();
                });
    }

    /**
     * For a given index of trajectories (timelines) in elasticsearch, runs through all trajectories, describing each event within them, then using that information
     * to generate a synthetic task description for the trajectory.
     *
     * All the generated event descriptions and synthetic tasks are stored in sqlite, tables 'trajectory_event_descriptions' and 'synthetic_tasks' respectively.
     * Additionally, the API calls encountered in each trajectory are also saved in sqlite, in the 'trajectory_api_calls' table.
     *
     * Finally, this method supports resuming its work in case it gets interrupted. It does this by querying the aforementioned sqlite tables to determine which trajectories and events have already been processed.
     */
    private void describeTrajectory(RoutingContext rc){
        List<Timeline> timelines = rc.get("timelines");

        Future.all(
                sqliteService.getSyntheticTaskTrajectoryIds(rc.get("index")),
                sqliteService.getEventDescriptionsTrajectoryIds(rc.get("index"))
        ).compose(trajectoryInfo->{
                    Set<String> completedTrajectories = trajectoryInfo.resultAt(0);
                    Set<String> partiallyAndFullyCompletedTrajectories = trajectoryInfo.resultAt(1);

                    //Now partiallyAndFullyCompletedTrajectories contains only trajectories that have been partially completed.
                    partiallyAndFullyCompletedTrajectories.removeAll(completedTrajectories);

                    //Filter out trajectories that have already been described.
                    List<Timeline> filteredTimelines = timelines.stream()
                            .filter(timeline->!completedTrajectories.contains(timeline.getId().toString()))
                            .collect(Collectors.toList());

                    Set<String> filteredTimelineIds = filteredTimelines.stream().map(Timeline::getId).map(UUID::toString).collect(Collectors.toSet());
                    partiallyAndFullyCompletedTrajectories = partiallyAndFullyCompletedTrajectories.stream().filter(trajectoryId->filteredTimelineIds.contains(trajectoryId)).collect(Collectors.toSet());

                    return Future.all(
                            sqliteService.getEventDescriptionsForTrajectories(partiallyAndFullyCompletedTrajectories),
                            Future.succeededFuture(filteredTimelines)
                    );

        }).compose((inProgressInfo)-> {
                    Set<JsonObject> eventDescriptionsFromInProgressTrajectories = inProgressInfo.resultAt(0);
                    List<Timeline> filteredTimelines = inProgressInfo.resultAt(1);

                    //Organize Event Descriptions by Trajectory ID
                    Map<String, List<EventDescription>> eventDescriptionsByTrajectoryId = new HashMap<>();
                    eventDescriptionsFromInProgressTrajectories.forEach(json -> {
                        Timeline correspondingTimeline = filteredTimelines.stream().filter(timeline -> timeline.getId().toString().equals(json.getString("trajectoryId"))).findFirst().get();
                        EventDescription eventDescription = EventDescription.fromJson(json, correspondingTimeline);
                        String trajectoryId = json.getString("trajectoryId");

                        if (eventDescriptionsByTrajectoryId.containsKey(trajectoryId)) {
                            eventDescriptionsByTrajectoryId.get(trajectoryId).add(eventDescription);
                        } else {
                            List<EventDescription> list = new ArrayList<>();
                            list.add(eventDescription);
                            eventDescriptionsByTrajectoryId.put(trajectoryId, list);
                        }
                    });

                    return Future.all(
                            Future.succeededFuture(eventDescriptionsByTrajectoryId),
                            Future.succeededFuture(filteredTimelines)
                    );

        }).compose(initializationData->{
                    Map<String, List<EventDescription>> eventDescriptionsByTrajectoryId = initializationData.resultAt(0);
                    List<Timeline> filteredTimelines = initializationData.resultAt(1);


            List<Future<String>> futures = new ArrayList<>();
            for (Timeline timeline: filteredTimelines){
                LabelTrajectoryTask task;
                //If we have partial results for this trajectory, initialize the labeling task with our existing work.
                if(eventDescriptionsByTrajectoryId.containsKey(timeline.getId().toString())){
                    List<EventDescription> existingEventDescriptions = eventDescriptionsByTrajectoryId.get(timeline.getId().toString());
                    task = new LabelTrajectoryTask(_config, timeline,existingEventDescriptions.size(), existingEventDescriptions);
                }else{
                    task = new LabelTrajectoryTask(_config, timeline);
                }



                task.setEventDescriptionConsumer((eventDescription)->{
                    sqliteService.saveTrajectoryEventDescription(
                            eventDescription.eventIndex(),
                            task.getTrajectory().getId().toString(),
                            rc.get("index"),
                            eventDescription.getDescription(),
                            eventDescription.getEntity().symbol(),
                            eventDescription.timestamp(),
                            _config.getJsonObject("openAI").getString("model")
                    );

                    if(eventDescription.getEntity() instanceof NetworkEvent networkEvent){
                        sqliteService.saveTrajectoryAPICall(
                                task.getTrajectory().getId().toString(),
                                eventDescription.eventIndex(),
                                networkEvent.getMethod(),
                                networkEvent.getPath(),
                                networkEvent.getGraphQLOperationName().orElse(null),
                                networkEvent.getRequestObject() != null?networkEvent.getRequestObject().encode():null,
                                networkEvent.getResponseObject() != null?networkEvent.getResponseObject().encode():null,
                                rc.get("index")
                        );
                    }
                });

                task.getPromise().future()
                        .onFailure(err->log.error(err.getMessage(), err))
                        .onSuccess(result->{

                            sqliteService.saveSyntheticTask(
                                    task.getTrajectory().getId().toString(),
                                    task.getSyntheticTaskDescription(),
                                    task.getTaskCreationTime().toString(),
                                    _config.getJsonObject("openAI").getString("model"),
                                    rc.get("index")
                            );

                        })
                ;
                futures.add(task.getPromise().future());
                executorService.submit(task);
            }

            return Future.all(futures);

        }).onSuccess(done->rc.next())
                .onFailure(err->log.error(err.getMessage(), err));

    }


    private void computeAnnotations(RoutingContext rc) {
        String sourceIndex = rc.request().getParam("srcIndex");
        int numPerm = rc.queryParam("numPerm").isEmpty()?512:Integer.parseInt(rc.queryParam("numPerm").get(0));
        int numShingles = rc.queryParam("numShingles").isEmpty()?5:Integer.parseInt(rc.queryParam("numShingles").get(0));

        elasticsearchService.fetchAll(sourceIndex)
                .compose(events->
                        //Filter out everything but click events
                        Future.succeededFuture(events.stream().filter(Predicates.annotationEventFilter()).toList()))
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(clickEvents->{
                        JsonArray results = new JsonArray();
                        Iterator<JsonObject> it = clickEvents.iterator();
                        Future<JsonObject> f = null;
                        while (it.hasNext()) {
                            JsonObject clickEvent = it.next();

                            JsonObject domSnapshotData = new JsonObject(clickEvent.getString("eventDetails_domSnapshot"));
                            String snapshotHTML = domSnapshotData.getString("outerHTML");
                            String targetElementXpath = clickEvent.getString("eventDetails_xpath");
                            String baseURI = clickEvent.containsKey("eventDetails_element")?new JsonObject(clickEvent.getString("eventDetails_element")).getString("baseURI"):null;

                            if (f == null){
                                f = cleanerService.toElementAnnotationQuery(snapshotHTML, targetElementXpath).compose(request->{
                                    request.put("baseURI", baseURI);
                                    return Future.succeededFuture(request);
                                }).compose(request->{
                                    return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/query")
                                            .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                            .addQueryParam("num_shingles", Integer.toString(numShingles))
                                            .sendJson(request)
                                            .onFailure(err->log.error(err.getMessage(), err))
                                            .compose(response->Future.succeededFuture(response.bodyAsJsonObject()));
                                }).compose(result->{
                                    results.add(result);
                                    return Future.succeededFuture(result);
                                });
                            }else{
                                f = f.compose(done->{
                                    return cleanerService.toElementAnnotationQuery(snapshotHTML, targetElementXpath).compose(request->{
                                        request.put("baseURI", baseURI);
                                        return Future.succeededFuture(request);
                                    }).compose(request->{
                                        return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/query")
                                                .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                                .addQueryParam("num_shingles", Integer.toString(numShingles))
                                                .sendJson(request)
                                                .onFailure(err->log.error(err.getMessage(), err))
                                                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()));
                                    }).compose(result->{
                                        results.add(result);
                                        return Future.succeededFuture(result);
                                    });
                                });
                            }
                        }

                        f.onSuccess(done->{
                            rc.response().setStatusCode(200).end(results.encodePrettily());
                        })
                                .onFailure(err->log.error(err.getMessage(), err));
                        }
                )

        ;

    }



    private void loadDOMSnapshots(RoutingContext rc){
        String sourceIndex = rc.queryParam("srcIndex").get(0);

        String eventBusAddress = UUID.randomUUID().toString();
        MessageConsumer messageConsumer = vertx.eventBus().consumer(eventBusAddress, msg->{
            JsonObject event = (JsonObject) msg.body();
            if(stateClusteringEventFilter().test(event)){

                JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                catalogHTMLAttributes(domSnapshotData.getString("outerHTML"))
                        .compose(done->Future.succeededFuture(domSnapshotData.getString("outerHTML")))
                        //.compose(done->cleanerService.cleanHTML(domSnapshotData.getString("outerHTML")))

                        .compose(html ->{
                            return sqliteService.saveDOMSnapshot(
                                    event.getString("mongo_id"),
                                    html,
                                    baseURI,
                                    sourceIndex

                            );

                        })
                        .onSuccess(done->log.info("Saved {} to Sqlite", event.getString("mongo_id")))
                        .onFailure(err->log.error("Error saving DOMSnapshot to SQLite. " + err.getMessage(), err ));;
            }
        });

        elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(done->{
                    messageConsumer.getDelegate().unregister();
                    rc.getDelegate().response().setStatusCode(200).end();
                });
    }

    //Save all the HTML attributes from an HTML document to sqlite
    private Future<Void> catalogHTMLAttributes(String html){
        Document document = Jsoup.parse(html);
        List<Future<Void>> attributeFutures = new ArrayList<>();

         class AttributeHarvester implements NodeVisitor{
             public List<JsonArray> attributeData = new ArrayList<>();
             @Override
             public void head(org.jsoup.nodes.Node node, int i) {
                 if(node instanceof Element){
                     Element element = (Element) node;
                     element.attributes().forEach(attribute -> {
                         attributeData.add(List.of(element.tagName(),attribute.getKey(), attribute.getValue() ).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll) );
                     });

                 }
             }


         }

        AttributeHarvester attributeHarvester = new AttributeHarvester();
        document.traverse(attributeHarvester);


        return sqliteService.saveHTMLAttributes(attributeHarvester.attributeData);
    }


    private void getClusterSnapshots(RoutingContext rc){
        log.info("Fetching relevant snapshots from sqlite...");
        Map<String, Set<String>> clusterMap = rc.get("clusterMap");

        try{
            List<JsonObject> results = new ArrayList<>();

            //Define a function that given a cluster Id and a set of document ids, retrieves the HTML for those documents from sqlite and produces a label for the cluster.
            BiFunction<String, Set<String>, Future<JsonObject>> func = (clusterId, documentIds)->{
                return sqliteService.getDomSnapshots(documentIds)
                        .compose(snapshots->stateLabelingService.generateStateLabeling(clusterId, snapshots))
                        .onFailure(err->log.error(err.getMessage(), err));
            };

            clusterMap.forEach((key, value) -> log.info("{} - {}", key, value));

            Iterator<Map.Entry<String, Set<String>>> clusterIterator = clusterMap.entrySet().iterator();
            Future<JsonObject> f = null;

            while (clusterIterator.hasNext()){
                Map.Entry<String, Set<String>> entry = clusterIterator.next();
                log.info("{} - {}", entry.getKey(), entry.getValue());
                if (f == null){
                    f = func.apply(entry.getKey(), entry.getValue()).compose(result->{
                        results.add(result);
                        return Future.succeededFuture();
                    });
                }else{
                    f = f.compose(done->func.apply(entry.getKey(), entry.getValue()).compose(result->{
                        results.add(result);
                        return Future.succeededFuture();
                    }));
                }

            }

            f.onFailure(err->log.error(err.getMessage(), err))
                    .onSuccess(done->{
                        log.info("Done creating cluster labels.");
                        rc.getDelegate().response().setStatusCode(200).end(results.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily());
                    });

        }catch (Exception e){
            log.error(e.getMessage(),e);
        }




    }

    /**
     * Detects click events in the nav model which match known common substructure xpaths.
     * Computes relevant dynamicXpaths for each match, and annotates the nav model nodes accordingly.
     * @param rc
     */
    private void detectDxpaths(RoutingContext rc){

        sqliteService.getUniqueCommonSubstructureContainers()
                .compose(substructures->{

                    JsonObject matches = new JsonObject();

                    try(var tx =  LogPreprocessor.graphDB.db.beginTx();
                        var result = tx.execute("MATCH (n:ClickNode) return n;");
                        ResourceIterator<Node> it = result.columnAs("n");
                    ){

                        while (it.hasNext()) {
                            org.neo4j.graphdb.Node node = it.next();
                            String nodeId = node.getProperty("id").toString();

                            Set<String> xpaths = new HashSet<>();

                            //Get xpath from regular click nodes
                            if(node.hasProperty("xpath")){
                                String xpath = "/";
                                xpath += node.getProperty("xpath");
                                xpaths.add(xpath);
                            }

                            //Get xpaths from collapsed click nodes
                            if(node.hasProperty("xpaths")){
                                String [] _xpaths = (String[]) node.getProperty("xpaths");

                                /**
                                 * Xpaths values are stored in the following format:
                                 * ["<baseURI>,<xpath>", ...]
                                 */
                                if(_xpaths.length > 0){
                                    xpaths = Arrays.stream(_xpaths)
                                            .map(entry->entry.split(",")[1])
                                            .map(entry->"/"+entry)
                                            .collect(Collectors.toSet());

                                }
                            }

                            Set<String> prefixesWithDynamicTags = substructures.stream()
                                    .map(item->item.getString("prefix") + "/" + item.getString("dynamic_tag"))
                                    .collect(Collectors.toSet());

                            for(String xpath: xpaths){
                                for(JsonObject substructure: substructures){
                                    String prefixAndDynamicTag = substructure.getString("prefix") + "/" + substructure.getString("dynamic_tag");
                                    String starredPrefixAndDynamicTag = prefixAndDynamicTag.replaceAll("[0-9]+", "*");
                                    String starredXpath = xpath.replaceAll("[0-9]+", "*");
                                    if(xpath.startsWith(prefixAndDynamicTag) || starredXpath.startsWith(starredPrefixAndDynamicTag)){

                                        if(matches.containsKey(nodeId)){
                                            //Update matches for this node id
                                            JsonObject nodeMatches = matches.getJsonObject(nodeId);
                                            if(!nodeMatches.getJsonArray("model_xpaths").stream()
                                                    .map(String.class::cast)
                                                    .collect(Collectors.toSet()).contains(xpath)){
                                                nodeMatches.getJsonArray("model_xpaths").add(xpath);
                                            };
//                                            if(!nodeMatches.getJsonArray("matched_substructures").stream()
//                                                    .map(JsonObject.class::cast)
//                                                    .map(entry->entry.getString("prefix") + "/" +  entry.getString("dynamic_tag"))
//                                                    .collect(Collectors.toSet()).contains(prefixAndDynamicTag)
//                                            ){
//                                                nodeMatches.getJsonArray("matched_substructures").add(substructure);
//                                            }
                                            nodeMatches.getJsonArray("matched_substructures").add(substructure);

                                        }else{
                                            //Initalize matches for this node id
                                            JsonObject nodeMatches = new JsonObject();
                                            nodeMatches.put("id", nodeId)
                                                    .put("model_xpaths", new JsonArray().add(xpath))
                                                    .put("matched_substructures", new JsonArray().add(substructure));
                                            matches.put(nodeId, nodeMatches);

                                        }


                                    }
                                }
                            }
                        }



                    }

                    return Future.succeededFuture(matches);
                }).compose(matches->{
                    //Convert matches into dynamic xpaths and annotate the nav model with them.
                    matches.forEach(entry->{
                        String nodeId = entry.getKey();
                        JsonObject matchInfo = (JsonObject) entry.getValue();

                        JsonArray modelXpaths = matchInfo.getJsonArray("model_xpaths");
                        JsonArray matchedSubstructures = matchInfo.getJsonArray("matched_substructures");

                        Set<String> htmlOfFalsePositives = new HashSet<>();

                        Set<DynamicXPath> dxpaths = modelXpaths.stream()
                                .map(String.class::cast)
                                .map(modelXpath->{


                                    Set<DynamicXPath> modelDXpaths = matchedSubstructures.stream()
                                            .map(JsonObject.class::cast)
                                            .map(structure->{
                                                DynamicXPath dxpath = new DynamicXPath();
                                                dxpath.setPrefix(structure.getString("prefix"));
                                                dxpath.setDynamicTag(structure.getString("dynamic_tag"));

                                                log.info("modelXpath: {}", modelXpath);


                                                String prefixAndDynamicTag = structure.getString("prefix") + "/" + structure.getString("dynamic_tag");
                                                log.info("\tprefixAndDynamicTag: {}", prefixAndDynamicTag);

                                                String starredPrefixAndDynamicTag = prefixAndDynamicTag.replaceAll("[0-9]+", "*");
                                                String starredXpath = modelXpath.replaceAll("[0-9]+", "*");

                                                if(!modelXpath.startsWith(prefixAndDynamicTag) && !starredXpath.startsWith(starredPrefixAndDynamicTag)){
                                                    //If this model path wasn't a match for this particular sub-structure, we cannot compute a dxpath for it.
                                                    return null;
                                                }
                                                /**
                                                 * The suffix is what is left of the model xpath after we remove the prefix, the dynamic tag and any dynamic tag index.
                                                 */
                                                String suffix = modelXpath.substring(prefixAndDynamicTag.length()); //Start after the dynamic tag

                                                if (suffix.contains("/")){
                                                    suffix = suffix.substring(suffix.indexOf("/") + 1); //And after any index associated with the dynamic tag in the model xpath
                                                    dxpath.setSuffixPattern(DynamicXPath.toSuffixPattern(List.of(suffix)));
                                                    dxpath.setKnownSuffixes(List.of(suffix));
                                                }else{
                                                    /*
                                                     * Sometimes there is no further suffix, the dynamic tag is all there is. Consider:
                                                     * /div/button[1]
                                                     * /div/button[2]
                                                     * /div/button[3]
                                                     *
                                                     * The dynamic tag is the button and there is no further suffix.
                                                     */

                                                    suffix = null;
                                                }

                                                //

                                                log.info("prefix: {}", structure.getString("prefix"));
                                                log.info("dynamicTag: {}", structure.getString("dynamic_tag"));
                                                log.info("suffix: {}", suffix);

                                                /*
                                                 * Validate the match to this sub-structure by ensuring that the computed suffix exists in the sub-structure's reference HTML.
                                                 */

                                                /**
                                                 * Need more careful handling for sub-structures in tables, as JSOUP doesn't parse those directly.
                                                 */
                                                String rawStructureHTML = structure.getString("html");
                                                if(rawStructureHTML.startsWith("<tr>") && rawStructureHTML.endsWith("</tr>")){
                                                    rawStructureHTML = "<table>%s</table>".formatted(rawStructureHTML);
                                                }

                                                Document structureHTMLSnippet = Jsoup.parseBodyFragment(rawStructureHTML);
                                                String validationXpath = "//" + structure.getString("dynamic_tag");
                                                if(suffix != null){
                                                    validationXpath += "/" + suffix;
                                                }
                                                Elements matchedSnippetElements = structureHTMLSnippet.selectXpath(validationXpath);

                                                if(matchedSnippetElements.size() > 0){
                                                    return dxpath;
                                                }else{

                                                    //Before giving up, try expanding the validation xpath.
                                                    List<String> otherCandidates = expandValidationXpaths(validationXpath);
                                                    for(String otherCandidate: otherCandidates){
                                                        matchedSnippetElements = structureHTMLSnippet.selectXpath(otherCandidate);
                                                        // Be stricter with these hypothetical xpath candidates and require them to resolve to a single element.
                                                        if(matchedSnippetElements.size() == 1){
                                                            return dxpath;
                                                        }
                                                    }

                                                    htmlOfFalsePositives.add(structure.getString("html"));
                                                    return null;
                                                }

                                                //return dxpath;
                                            })
                                            .filter(Objects::nonNull)
                                            .collect(Collectors.toSet());

                                    return modelDXpaths;

                                }).collect(HashSet::new, HashSet::addAll, HashSet::addAll);

                                //Annotate the nav model with the dxpaths we were able to compute if any
                                if (!dxpaths.isEmpty()){
                                    String dxpathsPropertyValue = dxpaths.stream()
                                            .map(DynamicXPath::toJson)
                                            .map(JsonObject::encode)
                                            .collect(JsonArray::new, JsonArray::add, JsonArray::addAll)
                                            .encode();


                                    try(var tx = LogPreprocessor.graphDB.db.beginTx();

                                    ){
                                        tx.execute("MATCH (n:ClickNode) WHERE n.id = '%s' SET n.dynamicXpaths = %s RETURN n;".formatted(nodeId,  dxpathsPropertyValue));
                                        tx.commit();
                                    }
                                }


                        //Remove false positive matches
                        Iterator<Object> it = matchedSubstructures.iterator();
                        while (it.hasNext()) {
                            JsonObject substructure = (JsonObject) it.next();
                            if(htmlOfFalsePositives.contains(substructure.getString("html"))){
                                it.remove();
                            }
                        }

                    });

                    /*
                     * It is possible that during false positive filtering, all substructure matches were false positives, if that is the case, remove the entry for the matches json object.
                     */
                    JsonObject filteredMatches  = matches.stream().filter(entry->{
                        JsonObject matchInfo = (JsonObject) entry.getValue();
                        return matchInfo.getJsonArray("matched_substructures").size() > 0;
                    }).collect(JsonObject::new, (json, entry)->json.put(entry.getKey(), entry.getValue()), JsonObject::mergeIn);




                    return Future.succeededFuture(filteredMatches);
                }).compose(matches->{
                    //Let's compute a nice little cypher query for convenience.
                    StringBuilder sb = new StringBuilder();
                    sb.append("MATCH (n) WHERE n.id IN [");
                    Iterator<Map.Entry<String,Object>> it = matches.stream().iterator();
                    while (it.hasNext()){
                        sb.append("'%s'".formatted(it.next().getKey()));
                        if(it.hasNext()){
                            sb.append(", ");
                        }
                    }
                    sb.append("] return n;");

                    matches.put("cypherQuery", sb.toString());
                    return Future.succeededFuture(matches);
                })

                .onFailure(err->log.error(err.getMessage(), err))
        .onSuccess(matches->rc.getDelegate().response().setStatusCode(200).end(matches.encodePrettily()));

        ;

    }

    /**
     * Given one xpath, produce other possible candidates that may match the intended target if there have been minor changes to the underlying html.
     *
     * The idea here is to try fiddling with xpath indices, for example:
     *
     * If this is the original xpath:
     * //div/div/div/span/span/span[2]/div/div/span[2]/span/span[1]/span[2]/div/div[2]/span/span/button
     *
     * This method will produce the following candidates:
     * //div/div/div/span/span/span[1]/div/div/span[2]/span/span[1]/span[2]/div/div[2]/span/span/button
     * //div/div/div/span/span/span[2]/div/div/span[1]/span/span[1]/span[2]/div/div[2]/span/span/button
     * //div/div/div/span/span/span[2]/div/div/span[2]/span/span[1]/span[1]/div/div[2]/span/span/button
     * //div/div/div/span/span/span[2]/div/div/span[2]/span/span[1]/span[2]/div/div[1]/span/span/button
     *
     * These candidates have almost the exact same structure as the original, but will match some cases where a single indexed position has been altered.
     *
     * The hypothesis is that, if you find exactly one element using one of the candidate xpaths, it's likely what you were initially looking for.
     *
     * @param xpath
     * @return
     */
    private List<String> expandValidationXpaths(String xpath){
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?<=\\[)[0-9]*(?=\\])");

        Matcher matcher = pattern.matcher(xpath);
        Iterator<MatchResult> it = matcher.results().iterator();
        while(it.hasNext()){
            MatchResult curr = it.next();
            int matchedIndex = Integer.parseInt(curr.group(0));

            while (--matchedIndex > 0){
                var candidate = xpath.substring(0, curr.start());
                candidate += Integer.toString(matchedIndex);
                candidate += xpath.substring(curr.end());
                result.add(candidate);
            }


        }

        return result;
    }

    private void processHrefs(RoutingContext rc){

        sqliteService.getDistinctHrefValues()
                .onSuccess(hrefs->{

                    Set<String> seenNormalizedHrefs = new HashSet<>();

                    Future.all(
                            hrefs.stream()
                                    .peek(href->log.info("{}", href))
                                    .filter(href->!href.contains("#") && !href.contains("?") && !href.contains("{") && !href.contains("%") && !href.contains("javascript") &&  !href.isBlank())
                                    .map(href->{
                                        String normalizedHref = Utils.normalizeBaseUri(href);
                                        if(seenNormalizedHrefs.contains(normalizedHref)){
                                            return Future.succeededFuture();
                                        }
                                        seenNormalizedHrefs.add(normalizedHref);

                                        return linkLabelingService.labelLink(href, normalizedHref)
                                                .compose(result->{
                                                    if(result.containsKey("type")){
                                                        return sqliteService.saveNormalizedLink(result.getString("normalizedHref"), result.getString("type"));
                                                    }else{
                                                        return Future.succeededFuture();
                                                    }
                                                })
                                                ;
                                    }).toList()

                    ).onFailure(err->log.error(err.getMessage(), err))
                                    .onSuccess(done->{
                                        log.info("Done processing hrefs, saving results");
                                        rc.getDelegate().response().setStatusCode(200).end();




                                    });





                })
                .onFailure(err->log.error(err.getMessage(), err));

    }

    //Only include clusters who's items share a parent element.
    private void resolveClusterNodesV2(RoutingContext rc){
        JsonObject result = new JsonObject();

        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");
        Document document = rc.get("document");

        Iterator<Map.Entry<String, List<JsonObject>>> it =  clusterMap.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String, List<JsonObject>> cluster = it.next();

            Set<Element> parents = new HashSet<>();

            //Filter out cluster items that don't have a robustXpath (IE: text nodes, #root, etc)
            JsonArray processedClusterItems = cluster.getValue().stream().filter(entry->entry.containsKey("robustXpath"))
                    .map(item->{
                        String robustXpath = item.getString("robustXpath");
                        Element element = document.selectXpath(robustXpath).get(0);
                        if(element.hasParent()){
                            parents.add(element.parent());
                        }
                        return element.html();
                    })
                    .filter(html->!html.isEmpty())
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            if (!processedClusterItems.isEmpty() && parents.size() == 1){
                //Only interested in clusters who share the same parent element
                result.put(cluster.getKey(), parents.iterator().next().html());
            }
        }

        rc.getDelegate().response().setStatusCode(200).end(result.encodePrettily());

    }

    private void resolveClusterNodes(RoutingContext rc){
        JsonObject result = new JsonObject();

        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");
        Document document = rc.get("document");

        Iterator<Map.Entry<String, List<JsonObject>>> it =  clusterMap.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String, List<JsonObject>> cluster = it.next();

            //Filter out cluster items that don't have a robustXpath (IE: text nodes, #root, etc)
            JsonArray processedClusterItems = cluster.getValue().stream().filter(entry->entry.containsKey("robustXpath"))
                    .map(item->{
                        String robustXpath = item.getString("robustXpath");
                        Element element = document.selectXpath(robustXpath).get(0);
                        return element.html();
                    })
                    .filter(html->!html.isEmpty())
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            if (!processedClusterItems.isEmpty()){
                result.put(cluster.getKey(), processedClusterItems);
            }
        }

        rc.getDelegate().response().setStatusCode(200).end(result.encodePrettily());

    }


    private void getNodeClusteringDocument(RoutingContext rc){
        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");

        if(clusterMap != null && clusterMap.size() > 0){
            Optional<Map.Entry<String, List<JsonObject>>> itemOptional =  clusterMap.entrySet().stream().filter(entry->entry.getValue().size()>0).findFirst();
            if(itemOptional.isPresent()){
                JsonObject clusterItem = itemOptional.get().getValue().get(0);
                String documentId = clusterItem.getString("id").split("_")[0];
                sqliteService.getDomSnapshots(Set.of(documentId))
                        .compose(docs->Future.succeededFuture(docs.get(0)))
                        .onSuccess(domSnapshot->{

                            String html = domSnapshot.getString("snapshot");
                            Document document = Jsoup.parse(html);
                            rc.put("document", document);
                            rc.put("snapshot", domSnapshot);
                            rc.next();
                        });
            }
        }

    }

    private void getNodeClustering(RoutingContext rc){
        String lshId = rc.get("lshId").toString();

        log.info("Fetching node clustering from LSH: {}",lshId);
        webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId)).send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onSuccess(response->{
                    JsonObject clusters = response.getJsonObject("clusters");
                    Map<String, List<JsonObject>> clusterMap = new HashMap<>();
                    clusters.forEach(entry->{
                        if(!entry.getKey().equals("-1")){
                            clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                    .map(o->(JsonObject)o)
                                    .collect(Collectors.toList())
                            );
                        }
                    });

                    rc.put("clusterMap", clusterMap);
                    rc.next();
                })
        ;
    }

    private void getClustering(RoutingContext rc){
        String lshId = rc.get("lshId").toString();

        log.info("Fetching clustering information from LSH: {}", lshId);
        webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId)).send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onSuccess(response->{

                        JsonObject clusters = response.getJsonObject("clusters");
                        Map<String, Set<String>> clusterMap = new HashMap<>();
                        clusters.forEach(entry->{
                            if(!entry.getKey().equals("-1")){ //Ignore the noise cluster produced by DBSCAN
                                /**
                                 * Cluster map contains <clusterId > : [list of document ids in the cluster..]
                                 * "1" -> ["696aa802acc7a9e8a7a4a4c0", "696aa802acc7a9e8a7a4a4cc", ...]
                                 */
                                clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                        .map(o->(JsonObject)o)
                                        .map(o->o.getString("id"))
                                        .collect(Collectors.toSet())
                                );
                            }
                        });

                    rc.put("clusterMap", clusterMap);
                    rc.next();


                })
        ;
    }

    private Future<String> makeMinhashLSHForSnapshotNodes(String snapshotId, String baseURI, String html, double threshold, int numPerm, int numShingles){

        Promise<String> promise = Promise.promise();


        webClient
                .post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH")
                .sendJson(new JsonObject()
                        .put("threshold", threshold)
                        .put("num_perm", numPerm)
                ).compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(response->{
                    String lshId = response.getString("id");

                    log.info("Created LSH model {} for DOMSnapshot {}", lshId, snapshotId );

                    cleanerService.toNodeLinks(html)
                            .compose(nodeLinks->{
                                nodeLinks.put("id", snapshotId);
                                nodeLinks.put("baseURI", baseURI);

                                return webClient.put(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId)
                                        .addQueryParam("num_shingles", Integer.toString(numShingles))
                                        .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                        .sendJson(new JsonArray().add(nodeLinks));
                            })
                            .onFailure(err->{
                                log.error(err.getMessage(),err);
                                promise.fail(err);
                            })
                            .onSuccess(done->{
                                promise.complete(lshId);
                            })
                    ;

                });

        return promise.future();

    }

    /**
     * like {@link #buildStateClusters(RoutingContext)} but pulls all documents into memory first. This allows a more controlled insertion into odo-LSH
     * @param rc
     */
    private void mineCommonDOMSubstructures(RoutingContext rc){
        String sourceIndex = rc.queryParam("srcIndex").get(0);

        //Minhash LSH parameters
        double threshold = rc.queryParam("threshold").isEmpty()?0.5:Double.parseDouble(rc.queryParam("threshold").get(0));
        int numPerm = rc.queryParam("numPerm").isEmpty()?256:Integer.parseInt(rc.queryParam("numPerm").get(0));
        int numShingles = rc.queryParam("numShingles").isEmpty()?5:Integer.parseInt(rc.queryParam("numShingles").get(0));

        //DBSCAN parameters
        double eps = rc.queryParam("eps").isEmpty()?0.9:Double.parseDouble(rc.queryParam("eps").get(0));
        int minSamples = rc.queryParam("minSamples").isEmpty()?2:Integer.parseInt(rc.queryParam("minSamples").get(0));

        String clusteringId = rc.queryParam("clusteringId").isEmpty()?UUID.randomUUID().toString():rc.queryParam("clusteringId").get(0);
        List<Future<?>> todo = List.of();
        if(!rc.queryParam("clusteringId").isEmpty()){
            //If the clustering id is prespecified, we don't need to save the clustering info to the database as it will already exist.
            todo = List.of(
                    sqliteService.getMinedSnapshotIds(clusteringId),
                    elasticsearchService.fetchAll(sourceIndex)
            );
        }else{
            todo = List.of(
                    sqliteService.getMinedSnapshotIds(clusteringId), //Get any existing progress towards mining common sub-structures for this clustering. Facilitates resuming interrupted mining.
                    elasticsearchService.fetchAll(sourceIndex), //Fetch the trajectory events containing DOMSnapshots for mining.
                    sqliteService.saveClusteringInfo(clusteringId, threshold, numPerm, eps, minSamples) //Save the parameters used for this clustering.
            );
        }


        Future.all(todo)

                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(compositeFuture->{

                    Set<String> minedSnapshotIds = compositeFuture.resultAt(0);
                    List<JsonObject> events = compositeFuture.resultAt(1);

                    log.info(events.iterator().next().encodePrettily());



                    List<JsonObject> selectedEvents = new ArrayList<>();

                    for (JsonObject event : events) {
                        if (stateClusteringEventFilter().test(event) &&
                                !minedSnapshotIds.contains(event.getString("mongo_id"))) { //Skip snapshots that we've already mined.

                            selectedEvents.add(event);
                        }
                    }


                    ListIterator<JsonObject> it =  selectedEvents.listIterator();
                    Future<Void> f = null;
                    while(it.hasNext()){
                        JsonObject event = it.next();
                        String snapshotId = event.getString("mongo_id");
                        String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;
                        JsonObject domSnapshot = new JsonObject(event.getString("eventDetails_domSnapshot"));
                        String snapshotHtml = domSnapshot.getString("outerHTML");

                        if (f == null){
                            f = mineCommonSubstructures(clusteringId, snapshotId, baseURI, snapshotHtml, threshold, numPerm, eps,  minSamples, numShingles);
                        }else{
                            f = f.compose(done->mineCommonSubstructures(clusteringId, snapshotId, baseURI, snapshotHtml, threshold, numPerm, eps, minSamples, numShingles));
                        }
                    }

                    f.onFailure(err->log.error(err.getMessage(),err))
                            .onSuccess(done->{
                                log.info("Finished mining {} DOMSnapshots for common substructures", selectedEvents.size());
                                rc.getDelegate().response().setStatusCode(200).end();
                            });




                });

    }

    private Future<Void> mineCommonSubstructures(String clusteringId, String snapshotId, String baseURI, String snapshotHtml, double threshold, int numPerm, double dbscanEps, int dbscanMinSamples, int numShingles){
        return Future.all(
                        Future.succeededFuture(snapshotHtml), //Get cleaned HTML snapshot
                        makeMinhashLSHForSnapshotNodes(snapshotId, baseURI, snapshotHtml, threshold, numPerm, numShingles)
                )
                .onFailure(err->log.error(err.getMessage(),err))
                .compose(compositeFuture->{
                    String snapshotHTML = (String) compositeFuture.list().get(0);
                    String lshId = (String) compositeFuture.list().get(1);

                    return Future.all(
                            getNodeClustering(lshId, Double.toString(dbscanEps), Integer.toString(dbscanMinSamples)),
                            Future.succeededFuture(snapshotHTML),
                            Future.succeededFuture(lshId)
                    );

                })
                .compose(compositeFuture -> {
                    Map<String, List<JsonObject>> clusterMap = (Map<String, List<JsonObject>>) compositeFuture.list().get(0);

                    Document snapshotDocument = Jsoup.parse((String)compositeFuture.list().get(1));
                    //snapshotDocument.traverse(new XpathSnapshotVisitor());
                    //Need to run through the outerHtml() cycle to ensure that the document is being processed the same as during fingerprint registration
                    String xpathAnnotatedHTML = snapshotDocument.outerHtml();


                    String cleanHTML = HTMLCleaningTools.clean(xpathAnnotatedHTML);
                    snapshotDocument = Jsoup.parse(cleanHTML);
                    snapshotDocument.traverse(new BlankRemovingVisitor());
                    snapshotDocument.traverse(new TagAndAttributeStrategy.PruningVisitor());


                    String lshId = (String) compositeFuture.list().get(2);

                    return Future.all(
                            processNodeClusters(clusterMap, snapshotId, snapshotDocument),
                            Future.succeededFuture(lshId)
                    );


                })
                .compose(compositeFuture->{

                    List<JsonObject> annotationCandidates = (List<JsonObject>) compositeFuture.list().get(0);
                    String lshId = (String) compositeFuture.list().get(1);

                    JsonArray parentIds = annotationCandidates.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                    log.info("Saving extracted substructures for snapshot: {} - {}", snapshotId, baseURI );
                    //Save all our work to SQLite
                    return Future.all(annotationCandidates.stream()
                            .map(candidate->{

                                List<Future<Void>> persistenceFutures = new ArrayList<>();
                                persistenceFutures.add(sqliteService.saveCommonSubstructureContainer(clusteringId, candidate));
                                persistenceFutures.addAll(candidate.getJsonArray("items").stream()
                                        .map(o->(JsonObject)o)
                                        .map(substructure->sqliteService.saveCommonSubstructure(clusteringId, substructure))
                                        .toList());

                                return Future.all(persistenceFutures);
                            })
                            .collect(Collectors.toList()))
                            //Tell OdoLSH to persist the parent nodes into redis
                            .compose(done->{


                                return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId + "/fingerprints")
                                        .sendJson(parentIds)
                                        .onFailure(err->log.error(err.getMessage(),err))

                                ;

                            })

                            .compose(done->{
                                log.info("Finished mining common substructures for clustering {} with DOMSnapshot {} - {} ", clusteringId, snapshotId, baseURI);
                                return Future.succeededFuture();
                    });

                });
    }

    private Future<List<JsonObject>> processNodeClusters(Map<String, List<JsonObject>> clusterMap, String snapshotId, Document document){
        List<JsonObject> annotationCandidates = new  ArrayList<>();

        TagAndAttributeStrategy.LabelingVisitor labelingNodeVisitor = new TagAndAttributeStrategy.LabelingVisitor();
        document.traverse(labelingNodeVisitor);

        log.info("processNodeClusters got {} nodes",  labelingNodeVisitor.nodeMap.size());

        Iterator<Map.Entry<String, List<JsonObject>>> it = clusterMap.entrySet().iterator();

        if(clusterMap.isEmpty()){ //Handle case where no cluster were detected.
            return Future.succeededFuture(annotationCandidates);
        }

        Future<JsonObject> f = null;
        while (it.hasNext()) {
            Map.Entry<String, List<JsonObject>> cluster = it.next();
            if (f == null) {
                f = processNodeCluster(cluster, snapshotId, document);
            }else {
                f = f.compose(candidate->{
                    if (candidate != null){
                        annotationCandidates.add(candidate);
                    }
                    return processNodeCluster(cluster, snapshotId, document);
                });
            }
        }

        return f.compose(finalCandidate->{
            if(finalCandidate != null){
                annotationCandidates.add(finalCandidate);
            }

            //Resolve parent node ids.
            Iterator<JsonObject> candidateIterator = annotationCandidates.iterator();
            while (candidateIterator.hasNext()) {
                JsonObject candidate = candidateIterator.next();
                Element parentElement = document.selectXpath(candidate.getString("parentXpath")).get(0);
                int parentIndex = labelingNodeVisitor.nodeIndex.get(parentElement);
                candidate.put("parentIndex", parentIndex);
                candidate.put("parentId", candidate.getString("snapshotId") + "_"+ parentIndex);
            }


            return Future.succeededFuture(annotationCandidates);
        });

    }

    private Future<JsonObject> processNodeCluster(Map.Entry<String, List<JsonObject>> cluster, String snapshotId, Document document){
        Set<Element> parents = new HashSet<>();

        JsonArray clusterItems = cluster.getValue().stream()
                .filter(entry->entry.containsKey("robustXpath"))
                .map(item->{
                    String robustXpath = item.getString("robustXpath");
                    String originalXpath = item.getString("oxp");
                    Element element = document.selectXpath(robustXpath).get(0);
                    if(element.hasParent()){
                        parents.add(element.parent());
                    }
                    return new JsonObject()
                            .put("snapshotId", snapshotId )
                            .put("clusterId",  cluster.getKey())
                            .put("nodeId", item.getString("id").split("_")[1])
                            .put("fullNodeId", item.getString("id"))
                            .put("robustXpath", robustXpath)
                            .put("originalXpath", originalXpath)
                            .put("html", element.outerHtml());
                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        if(!clusterItems.isEmpty() && parents.size() == 1){
            Element parentElement = parents.iterator().next();


            return vertx.getDelegate().executeBlocking(()->{
                    try{
                        return computeXpathNoRoot(parentElement);
                        //return robulaPlus.getRobustXPath(parentElement, document);
                    }catch (IllegalStateException e){
                        return null;
                    }catch(IllegalArgumentException e){
                        return null;
                    }
                    })
                    .compose(parentXpath->{
                        JsonObject annotationCandidate = new JsonObject()
                                .put("snapshotId", snapshotId)
                                .put("clusterId", cluster.getKey())
                                .put("parentXpath", parentXpath != null? parentXpath : "null")
                                .put("parentHtml", parentElement.outerHtml())
                                .put("items", clusterItems);

                        return Future.succeededFuture(annotationCandidate);
                    });
        }else{
            return Future.succeededFuture(null);
        }
    }

    private Future<Map<String, List<JsonObject>>> getNodeClustering(String lshId, String dbscanEPS, String dbscanMinSamples ){
        log.info("Fetching node clustering from LSH: {}",lshId);
        return webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId))
                .addQueryParam("eps", dbscanEPS)
                .addQueryParam("min_samples", dbscanMinSamples)
                .send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .compose(response->{
                    JsonObject clusters = response.getJsonObject("clusters");
                    Map<String, List<JsonObject>> clusterMap = new HashMap<>();
                    clusters.forEach(entry->{
                        if(!entry.getKey().equals("-1")){ //Ignore noise cluster produced by DBSCAN
                            clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                    .map(o->(JsonObject)o)
                                    .collect(Collectors.toList())
                            );
                        }
                    });

                    return Future.succeededFuture(clusterMap);
                })
        ;
    }

    private void buildStateClusters(RoutingContext rc){

        String sourceIndex = rc.queryParam("srcIndex").get(0);
        String lshName = rc.queryParam("targetLSH").get(0);
        double threshold = rc.queryParam("threshold").isEmpty()?0.5:Double.parseDouble(rc.queryParam("threshold").get(0));
        int numPerm = rc.queryParam("numPerm").isEmpty()?256:Integer.parseInt(rc.queryParam("numPerm").get(0));
        int numShingles = rc.queryParam("numShingles").isEmpty()?5:Integer.parseInt(rc.queryParam("numShingles").get(0));


        //Create an LSH model for clustering the DOMSnapshots.
        webClient
                .post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH")
                .sendJson(new JsonObject()
                        .put("threshold", threshold)
                        .put("name", lshName)
                        .put("num_perm", numPerm)
                ).compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(response->{
                    String lshId = response.getString("id");
                    log.info("New LSH created with id {}", lshId);
                    rc.put("lshId", lshId);
                    //Register an eventbus listener to process the documents/events from elasticsearch.
                    String eventBusAddress = "%s-hashingProcessor".formatted(lshId);

                    MessageConsumer eventBusListener = vertx.eventBus().consumer(eventBusAddress, msg->{
                        JsonObject event = (JsonObject) msg.body();
                        if(stateClusteringEventFilter().test(event)){

                            JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                            String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                            cleanerService.toNodeLinks(domSnapshotData.getString("outerHTML"))
                                    .compose(nodeLinks->{
                                        nodeLinks.put("id", event.getString("mongo_id"));
                                        nodeLinks.put("baseURI", baseURI==null?"-":baseURI);

                                        return webClient.put(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId)
                                                .addQueryParam("num_shingles", Integer.toString(numShingles))
                                                .sendJson(new JsonArray().add(nodeLinks));
                                    });

                        }
                    });

                    elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                            .onFailure(err->log.error(err.getMessage(), err))
                            .onSuccess(done->{
                                log.info("Done processing events, unregistering event bus listener!");
                                eventBusListener.getDelegate().unregister();
                                rc.next();
                            });


                });

    }

    /**
     * Returns a single DOMSnapshot from the provided elasticsearch index.
     * @param rc
     */
    private void sampleDOMSnapshot(RoutingContext rc) {
        String esIndex = rc.queryParam("index").get(0);
        String eventType = rc.queryParam("eventDetails_name").get(0);

        elasticsearchService.fetchAll(esIndex).onSuccess(events -> {
            log.info("got events");
            Iterator<JsonObject> it = events.iterator();
            JsonObject event = it.next();
            //Randomly look for an event that has the specified name and contains a DOMSnapshot
            while (!event.containsKey("eventDetails_domSnapshot") &&  event.getString("eventDetails_name").equals(eventType)) {
                log.info("contains eventDetails_domSnapshot: {}", event.containsKey("eventDetails_domSnapshot"));
                log.info("eventDetails_name: {}", event.getString("eventDetails_name"));
                event = it.next();
            }

            JsonObject data = new JsonObject(event.getString("eventDetails_domSnapshot"));
            rc.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(data.getString("outerHTML"));

        }).onFailure(throwable -> log.error(throwable.getMessage(), throwable));
    }

    private void nodeLinks(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.toNodeLinks(html)
                .onSuccess(graph -> rc.response().setStatusCode(200).end(graph.encodePrettily()))
                .onFailure(throwable -> log.error(throwable.getMessage(), throwable));
        ;
    }

    private void clean(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.cleanHTML(html)
                .onSuccess(result -> rc.getDelegate().response().setStatusCode(200).end(result))
                .onFailure(ex -> rc.getDelegate().response().setStatusCode(500).end(ex.getMessage()));
        ;

    }
}
