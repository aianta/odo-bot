package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.SqliteVectorService;
import ca.ualberta.odobot.taskplanner.AIStrategy;
import ca.ualberta.odobot.taskplanner.Strategy;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class TaskPlannerServiceImpl implements TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerServiceImpl.class);

    private Vertx vertx;
    private Neo4JUtils neo4j;

    private SqliteService sqlite;
    private SqliteVectorService vectorService;

    private JsonObject config;

    private AIStrategy strategy;
    public static String model;

    public TaskPlannerServiceImpl(JsonObject config, Vertx vertx, SqliteService sqliteService, SqliteVectorService vectorService, Neo4JUtils neo4j, Strategy strategy){
        this.vertx = vertx;
        this.config = config;
        this.sqlite = sqliteService;
        this.neo4j = neo4j;
        this.vectorService = vectorService;

        this.strategy = switch (strategy){
            case OPENAI -> {
                OpenAIStrategy _strategy = new OpenAIStrategy(config);
                RequestManager.newTokenUsageRecordListeners.add(_strategy::onNewTokenUsageRecord);
                model = _strategy.getModel();
                yield _strategy;
            }
        };
    }

    public Future<String> generateNodeAnnotation(List<String> descriptions){
        return this.strategy.generateNodeAnnotation(descriptions);
    }

    public Future<JsonObject> pickMostRelevantTask(String queryTask, List<JsonObject> options){
        return this.strategy.pickMostRelevantTask(queryTask, options);
    }


    public Future<String> selectPath(JsonObject paths, String taskDescription){

        return vertx.<String>executeBlocking(blocking->{
            this.strategy.selectPath(paths, taskDescription)
                    .onSuccess(pathId->blocking.complete(pathId))
                    .onFailure(err->blocking.fail(err));
        });
    }

    public Future<String> rewriteQueryTaskWithoutSpecificInputs(String queryTask, List<JsonObject> syntheticTasks){
        return this.strategy.rewriteQueryTaskWithoutSpecificInputs(queryTask, syntheticTasks);
    }



    public Future<JsonObject> taskQueryConstructionV2(JsonObject task){
        log.info("Performing Task Query Construction");
        String taskDescription = task.getString("task");

        return Future.all(
                //Resolve input parameter mappings for the task.
                this.getInputParameterMappings(taskDescription),
                //Resolve resource parameter mappings for the task.
                this.getRelevantResourceParameters(taskDescription),
                //Resolve target API calls for the task.

                sqlite.getSyntheticTasks().compose(syntheticTasks->{
                    return Future.all(
                            Future.succeededFuture(syntheticTasks),
                            //Rewrite the given task omitting input values in order to improve accuracy of targeting. Need synthetic tasks for this to give the LLM examples of the style to rewrite in.
                            this.strategy.rewriteQueryTaskWithoutSpecificInputs(taskDescription, syntheticTasks)
                    );
                }).compose( results->{
                        List<JsonObject> syntheticTasks = results.resultAt(0);
                        String rewrittenTask = results.resultAt(1);

                        log.info("Rewrote task:\n{}\nto:\n{}", taskDescription, rewrittenTask);
                        task.put("rewrittenTo", rewrittenTask);
                        return Future.all(
                                vectorService.topK(5, rewrittenTask),
                                Future.succeededFuture(syntheticTasks)
                        );
                        }

                ).compose(targetingQueryResult->{
                    List<JsonObject> hits = targetingQueryResult.resultAt(0);
                    List<JsonObject> syntheticTasks = targetingQueryResult.resultAt(1);

                    List<JsonObject> matchedTasks = syntheticTasks.stream()
                            .filter(mTask->hits.stream().map(json->json.getString("trajectoryId")).toList().contains(mTask.getString("id"))).toList();

                    //Merge in query distance
                    matchedTasks.stream().forEach(mTask->{
                        mTask.put("distance",hits.stream().filter(hit->hit.getString("trajectoryId").equals(mTask.getString("id"))).findFirst().get().getFloat("distance"));;
                    });

                    return Future.all(
                            matchedTasks.stream()
                                    .map(matchedTask->sqlite.getAPICallsForTrajectory(matchedTask.getString("id"))
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
                    }).compose(options->{

                        //Pick the correct task from a list of likely options.
                        return this.strategy.pickMostRelevantTask(taskDescription, options.stream().map(JsonObject.class::cast).toList());
                    });

                })

        ).onFailure(err->{
            log.error("Error while performing task query construction");
            log.error(err.getMessage(), err);
        })
                .compose(compositeFuture -> {

                    try {

                        //Extract the results from the composite future.
                        log.info("Got input parameter mappings");
                        List<JsonObject> inputParameterMappings = compositeFuture.resultAt(0);
                        log.info("Got resource parameters mappings");
                        List<JsonObject> resourceParameters = compositeFuture.resultAt(1);
                        log.info("Got API target query results");
                        JsonArray apiCalls = new JsonArray().add(compositeFuture.resultAt(2));




                        JsonObject result =  new JsonObject();
                        result.put("id", task.getString("id"));
                        result.put("userLocation", task.getString("userLocation"));
                        result.put("targets", processTargetingResults(apiCalls));

                        //Compute input parameters in task format for odobot.
                        JsonArray parameters = inputParameterMappings.stream()
                                .map(inputParam->{
                                    JsonObject _param = new JsonObject()
                                            .put("id", inputParam.getString("id"))
                                            .put("type", "InputParameter")
                                            .put("value", inputParam.getString("value"));
                                    return _param;
                                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                        //Add schema/object parameters
                        parameters.addAll(
                                resourceParameters.stream()
                                        .map(objectParam->{
                                            JsonObject _param = new JsonObject()
                                                    .put("id", objectParam.getString("id"))
                                                    .put("type", "ResourceParameter")
                                                    .put("query", objectParam.getString("query"))
                                                    .put("name", objectParam.getString("name"));
                                            return _param;
                                        }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll)
                        );

                        result.put("parameters", parameters);
                        result.put("_evalId", task.getString("_evalId"));
                        result.put("rewrittenTo", task.getString("rewrittenTo"));

                        log.info("Completed task query construction!");

                        return Future.succeededFuture(result);

                    }catch (Exception e){
                        log.error(e.getMessage(), e);
                        return Future.failedFuture(e);
                    }

                });
    }

    public Future<JsonObject> taskQueryConstruction(JsonObject task){
        log.info("Performing Task Query Construction");
        String taskDescription = task.getString("task");

        return Future.all(
                //Resolve input parameter mappings for the task.
                this.getInputParameterMappings(taskDescription),
                //Resolve resource parameter mappings for the task.
                this.getRelevantResourceParameters(taskDescription),
                //Resolve target API calls for the task.
                this.getRelevantAPICalls(taskDescription)
        ).onFailure(err->{
            log.error("Error while performing task query construction");
            log.error(err.getMessage(), err);
                })
                .compose(compositeFuture -> {

            try{
                //Extract the results from the composite future.
                log.info("Got input parameter mappings");
                List<JsonObject> inputParameterMappings = compositeFuture.resultAt(0);
                log.info("Got resource parameters mappings");
                List<JsonObject> resourceParameters = compositeFuture.resultAt(1);
                log.info("Got API call mappings");
                List<JsonObject> apiCalls = compositeFuture.resultAt(2);

                JsonObject result =  new JsonObject();
                result.put("id", task.getString("id"));
                result.put("userLocation", task.getString("userLocation"));
                result.put("targets", apiCalls.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

                //Compute input parameters in task format for odobot.
                JsonArray parameters = inputParameterMappings.stream()
                        .map(inputParam->{
                            JsonObject _param = new JsonObject()
                                    .put("id", inputParam.getString("id"))
                                    .put("type", "InputParameter")
                                    .put("value", inputParam.getString("value"));
                            return _param;
                        }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                //Add schema/object parameters
                parameters.addAll(
                        resourceParameters.stream()
                                .map(objectParam->{
                                    JsonObject _param = new JsonObject()
                                            .put("id", objectParam.getString("id"))
                                            .put("type", "ResourceParameter")
                                            .put("query", objectParam.getString("query"))
                                            .put("name", objectParam.getString("name"));
                                    return _param;
                                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll)
                );

                result.put("parameters", parameters);
                result.put("_evalId", task.getString("_evalId"));

                log.info("Completed task query construction!");

                return Future.succeededFuture(result);
            }catch (Exception e){
                log.error(e.getMessage(), e);
                return Future.failedFuture(e);
            }

        });

    }

    private JsonArray processTargetingResults(JsonArray queryResults){

        //Expect top-1 match
        assert queryResults.size() == 1;

        JsonObject matchedTask = queryResults.getJsonObject(0);
        JsonArray apiCalls = matchedTask.getJsonArray("apiCalls");

        //TODO: This is a hack, I am ignoring the login API call that is returned. We need proper task composition support to do this right.
        //I am also picking the last API call. Thus our trajectories must end on the API call that completes the task.
        apiCalls = new JsonArray().add(apiCalls.getJsonObject(apiCalls.size()-1));
        //apiCalls = apiCalls.stream().map(JsonObject.class::cast).filter(call->!call.getString("path").equals("/login/canvas")).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        assert apiCalls.size() == 1;

        List<JsonObject> modelAPICalls = neo4j.getAllAPINodes()
                .stream()
                .map(apiNode -> new JsonObject()
                        .put("method", apiNode.getMethod())
                        .put("path", apiNode.getPath())
                        .put("id", apiNode.getId().toString())
                ).collect(Collectors.toList());

        modelAPICalls.addAll(
                neo4j.getAllGraphQLNodes()
                        .stream()
                        .map(graphQLNode -> new JsonObject()
                                .put("method", graphQLNode.getMethod())
                                .put("path", graphQLNode.getPath())
                                .put("operationName", graphQLNode.getOperationName())
                                .put("id", graphQLNode.getId().toString())
                        ).toList()
        );

        JsonObject targetAPICall = apiCalls.getJsonObject(0);

        //Look through the api calls in the model and find the one that matches the one returned by the targeting mechanism.
        JsonObject targetModelAPICall = modelAPICalls.stream().filter(modelCall->{
            if(targetAPICall.containsKey("operationName")){
                //If there is no operationName key in the model call it cannot match a targetAPI call that does specify one.
                if(!modelCall.containsKey("operationName")){
                    return false;
                }

                return modelCall.getString("operationName").equals(targetAPICall.getString("operationName")) &&
                        modelCall.getString("method").equals(targetAPICall.getString("method")) &&
                                modelCall.getString("path").equals(targetAPICall.getString("path"));
            }else{
                return modelCall.getString("method").equals(targetAPICall.getString("method")) &&
                        modelCall.getString("path").equals(targetAPICall.getString("path"));
            }
        }).findFirst().get();

        targetModelAPICall.put("targetingTaskId", matchedTask.getString("id"))
                .put("targetingTaskResult", matchedTask.getString("task"));

        return new JsonArray().add(targetModelAPICall);
    }

    public Future<List<JsonObject>> getRelevantResourceParameters(String taskDescription){
        log.info("getRelevantResourceParameters");
        return sqlite.getResourceParameterLabels()
                .compose(labels->this.strategy.getTaskResourceParameters(taskDescription, labels.stream().toList()))
                //Resolve node ids for identified resource parameters.
                .compose(mapping->{
                    List<JsonObject> finalMapping = new ArrayList<>();
                    for(JsonObject param: mapping){
                        String parameterNodeId = neo4j.getNodeIdByResourceParameterName(param.getString("name"));
                        if(parameterNodeId != null){
                            param.put("id", parameterNodeId);
                            finalMapping.add(param);
                        }

                    }
                    return Future.succeededFuture(finalMapping);
                });
    }

    @Override
    public Future<List<JsonObject>> getRelevantObjectParameters(String taskDescription) {
        //Execute in a separate thread.


            //Fetch the schemas/objects from sqlite
            return sqlite.getSemanticSchemas()
                    .onFailure(err->log.error(err.getMessage(), err))
                    //Filter duplicates
                    .compose(schemas->{
                        Set<String> names = new HashSet<>();
                        List<SemanticSchema> noDuplicates = new ArrayList<>();
                        for(SemanticSchema schema: schemas){
                            if(!names.contains(schema.getName())){
                                noDuplicates.add(schema);
                                names.add(schema.getName());
                            }
                        }
                        return Future.succeededFuture(noDuplicates);
                    })
                    //Prompt the LLM to identify relevant ones.
                    .compose(schemas->this.strategy.getTaskSchemas(taskDescription, schemas))
                    //Resolve schema parameter node ids, and replace the schemaIds with these nodeIds.
                    //TODO: Should schemaIds even be a separate thing from the ids of the nodes in which they're stored in the nav model?
                    .compose(schemas->{
                        for(JsonObject schema: schemas){
                            schema.put("id", neo4j.getNodeIdBySchemaName(schema.getString("name")));
                        }
                        return Future.succeededFuture(schemas);
                    });


    }

    @Override
    public Future<List<JsonObject>> getInputParameterMappings(String taskDescription) {

            return sqlite.getAllDataEntryAnnotations()
                    .onFailure(err->log.error(err.getMessage(), err))
                    .compose(dataEntryAnnotations->this.strategy.getTaskInputParameterMappings(taskDescription, dataEntryAnnotations))
                    .compose(chosenParameters->{
                        for(JsonObject entry: chosenParameters){
                            String resolvedId = null;
                            if(entry.containsKey("radioGroup")){
                                resolvedId = neo4j.getRadioButtonNodeId(entry.getString("radioGroup"));
                            }else{
                                resolvedId = neo4j.getInputParameterId(entry.getString("label"));
                            }
                            if (resolvedId != null){
                                //Add the id of each associated data entry or checkbox node
                                entry.put("id", resolvedId );
                            }else{
                                return Future.failedFuture("Failed to resolve id for parameter "+entry.getString("label"));
                            }

                        }
                        return Future.succeededFuture(chosenParameters);
                    });

    }

    @Override
    public Future<List<JsonObject>> getRelevantAPICalls(String taskDescription) {




            List<JsonObject>  apiCalls = neo4j.getAllAPINodes()
                    .stream()
                    .map(apiNode -> new JsonObject()
                            .put("method", apiNode.getMethod())
                            .put("path", apiNode.getPath())
                            .put("id", apiNode.getId().toString())
                    ).collect(Collectors.toList());

            apiCalls.addAll(
                    neo4j.getAllGraphQLNodes().stream()
                            .map(graphQLNode -> new JsonObject()
                                    .put("method", graphQLNode.getMethod())
                                    .put("path", graphQLNode.getPath())
                                    .put("operationName", graphQLNode.getOperationName())
                                    .put("id", graphQLNode.getId().toString())
                            ).toList()
            );

            return this.strategy.getTaskAPICalls(taskDescription, apiCalls);


    }

    @Override
    public Future<String> resolveDataEntryValue(String taskDescription, String inputParameterId, String currentValue) {

        //Figure out the xpath of the input parameter we're talking about
        String inputParameterXpath = neo4j.getAllInputParameterNodes().stream().filter(inputParameterNode->inputParameterNode.get("id").asString().equals(inputParameterId))
                .map(node->node.get("xpath").asString())
                .findFirst().get();

        //Use that xpath to retrieve all known info about that data entry, combine that with the task description and ask the LLM to spit out an appropriate value.
        return sqlite.getAllDataEntryInfoForXpath(inputParameterXpath)
                .compose(dataEntryInfo->{
                    return this.strategy.resolveDataEntryValue(taskDescription,
                            dataEntryInfo.getString("inputElementHTML"),
                            dataEntryInfo.getString("htmlContext"),
                            dataEntryInfo.getJsonArray("enteredData").stream().map(String.class::cast).toList(),
                            dataEntryInfo.getString("label"),
                            dataEntryInfo.getString("description"),
                            currentValue
                    );
                });


    }


    @Override
    public Future<JsonObject> resolveRadioButtonAction(JsonArray state, String taskDescription, String inputParameterId) {

        //Figure out the xpath of the input parameter we're talking about
        String inputParameterXpath = neo4j.getAllInputParameterNodes().stream().filter(inputParameterNode->inputParameterNode.get("id").asString().equals(inputParameterId))
                .map(node->node.get("xpath").asString())
                .findFirst().get();

        //Use that xpath to retrieve all known info about that data entry, combine that with the task description and ask the LLM to spit out an appropriate value.
        return sqlite.getAllDataEntryInfoForXpath(inputParameterXpath)
                .compose(dataEntryInfo->{
                    return this.strategy.resolveRadioButtonAction(
                            state,
                            taskDescription,
                            dataEntryInfo.getString("htmlContext"),
                            dataEntryInfo.getString("label"),
                            dataEntryInfo.getString("description")
                    );
                });
    }

    @Override
    public Future<JsonObject> resolveSelectAction(JsonArray state, String taskDescription, String inputParameterId) {
        //Figure out the xpath of the input parameter we're talking about
        String inputParameterXpath = neo4j.getAllInputParameterNodes().stream().filter(inputParameterNode->inputParameterNode.get("id").asString().equals(inputParameterId))
                .map(node->node.get("xpath").asString())
                .findFirst().get();

        //Use that xpath to retrieve all known info about that data entry, combine that with the task description and ask the LLM to spit out an appropriate value.
        return sqlite.getAllDataEntryInfoForXpath(inputParameterXpath)
                .compose(dataEntryInfo->{
                    return this.strategy.resolveSelectAction(
                            state,
                            taskDescription,
                            dataEntryInfo.getString("inputElementHTML"),
                            dataEntryInfo.getString("htmlContext"),
                            dataEntryInfo.getString("label"),
                            dataEntryInfo.getString("description")
                    );

                });


    }

    @Override
    public Future<Boolean> resolveCheckboxAction(JsonObject state, String taskDescription, String inputParameterId) {
        return null;
    }
}
