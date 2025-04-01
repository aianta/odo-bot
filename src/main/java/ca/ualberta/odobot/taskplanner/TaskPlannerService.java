package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;

import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.impl.TaskPlannerServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface TaskPlannerService {

    static TaskPlannerService create(Vertx vertx, JsonObject config, SqliteService sqliteService, Neo4JUtils neo4j){
        return new TaskPlannerServiceImpl( config, vertx, sqliteService, neo4j, Strategy.OPENAI);
    }

    static TaskPlannerService createProxy(Vertx vertx, String address){
        return new TaskPlannerServiceVertxEBProxy(vertx, address);
    }


    /**
     * Accepts a task definition in natural language, and produces an executable task execution request.
     * @param task A json object with the task details. It should include the following fields 'id', '_evalId',
     *             'task', and  'userLocation'.
     * @return A task execution request in JSON format, contains 'targets' and 'parameters' fields populated
     * from the task description.
     */
    Future<JsonObject> taskQueryConstruction(JsonObject task);

    /**
     * Given a set of nav paths, select the one which best aligns with the task description.
     *
     * @param paths a JsonObject whose keys are NavPath IDs and whose values are JsonArrays of the natural language representation of that navPath.
     * @param taskDescription
     * @return
     */
    Future<String> selectPath(JsonObject paths, String taskDescription);

    Future<List<JsonObject>> getRelevantObjectParameters(String taskDescription);

    Future<List<JsonObject>> getInputParameterMappings(String taskDescription);

    Future<List<JsonObject>> getRelevantAPICalls(String taskDescription);
}
