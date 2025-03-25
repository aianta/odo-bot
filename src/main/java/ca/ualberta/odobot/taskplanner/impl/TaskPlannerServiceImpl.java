package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.GraphDB;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.APINode;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.AIStrategy;
import ca.ualberta.odobot.taskplanner.Strategy;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
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


    @Override
    public Future<List<JsonObject>> getRelevantObjectParameters(String taskDescription) {
        //Execute in a separate thread.
        return vertx.<List<JsonObject>>executeBlocking(blocking->{

            //Fetch the schemas/objects from sqlite
            sqlite.getSemanticSchemas()
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
                            schema.put("id", neo4j.getNodeIdBySchemaId(schema.getString("id")));
                        }
                        return Future.succeededFuture(schemas);
                    })
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
            ;
        });

    }

    @Override
    public Future<List<JsonObject>> getInputParameterMappings(String taskDescription) {
        return vertx.<List<JsonObject>>executeBlocking(blocking->{
            sqlite.getAllDataEntryAnnotations()
                    .onFailure(err->log.error(err.getMessage(), err))
                    .compose(dataEntryAnnotations->this.strategy.getTaskInputParameterMappings(taskDescription, dataEntryAnnotations))
                    .compose(chosenParameters->{
                        for(JsonObject entry: chosenParameters){
                            //Add the id of each associated data entry or checkbox node
                            entry.put("id", neo4j.getInputParameterId(entry.getString("xpath")));
                        }
                        return Future.succeededFuture(chosenParameters);
                    })
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }

    @Override
    public Future<List<JsonObject>> getRelevantAPICalls(String taskDescription) {

        return vertx.<List<JsonObject>>executeBlocking(blocking->{
            List<JsonObject>  apiCalls = neo4j.getAllAPINodes()
                    .stream()
                    .map(apiNode -> new JsonObject()
                            .put("method", apiNode.getMethod())
                            .put("path", apiNode.getPath())
                            .put("id", apiNode.getId().toString())
                    ).collect(Collectors.toList());

            this.strategy.getTaskAPICalls(taskDescription, apiCalls)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });

    }
}
