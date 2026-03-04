package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
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

    private JsonObject config;

    private AIStrategy strategy;

    public TaskPlannerServiceImpl(JsonObject config, Vertx vertx, SqliteService sqliteService, Neo4JUtils neo4j, Strategy strategy){
        this.vertx = vertx;
        this.config = config;
        this.sqlite = sqliteService;
        this.neo4j = neo4j;

        this.strategy = switch (strategy){
            case OPENAI -> new OpenAIStrategy(config);
        };
    }


    public Future<String> selectPath(JsonObject paths, String taskDescription){

        return vertx.<String>executeBlocking(blocking->{
            this.strategy.selectPath(paths, taskDescription)
                    .onSuccess(pathId->blocking.complete(pathId))
                    .onFailure(err->blocking.fail(err));
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
                            //Add the id of each associated data entry or checkbox node
                            entry.put("id", neo4j.getInputParameterId(entry.getString("label")));
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
}
