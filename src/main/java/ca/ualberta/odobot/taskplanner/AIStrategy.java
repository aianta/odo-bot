package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {


    Future<List<JsonObject>> getTaskSchemas(String taskDescription, List<SemanticSchema> options);

    Future<List<JsonObject>> getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations);

    Future<List<JsonObject>> getTaskAPICalls(String taskDescription, List<JsonObject> apiCalls);

    Future<String> selectPath(JsonObject paths, String taskDescription);
}
