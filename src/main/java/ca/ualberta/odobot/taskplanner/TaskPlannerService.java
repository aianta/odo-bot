package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.taskplanner.impl.TaskPlannerServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
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


    Future<List<JsonObject>> getRelevantObjectParameters(String taskDescription);

    Future<List<JsonObject>> getInputParameterMappings(String taskDescription);

    Future<List<JsonObject>> getRelevantAPICalls(String taskDescription);
}
