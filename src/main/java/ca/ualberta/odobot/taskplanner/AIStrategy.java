package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {

    Future<String> generateNodeAnnotation(List<String> descriptions);

    Future<JsonObject> pickMostRelevantTask(String queryTask, List<JsonObject> options);

    Future<String> rewriteQueryTaskWithoutSpecificInputs(String queryTask, List<JsonObject> syntheticTasks);

    Future<List<JsonObject>> getTaskSchemas(String taskDescription, List<SemanticSchema> options);

    Future<List<JsonObject>> getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations);

    Future<List<JsonObject>> getTaskResourceParameters(String taskDescription, List<String> options);

    Future<List<JsonObject>> getTaskAPICalls(String taskDescription, List<JsonObject> apiCalls);

    Future<JsonObject> selectPath(JsonObject paths, String taskDescription);

    Future<String> resolveDataEntryValue(String taskDescription, String inputElementHTML, String htmlContext, List<String> exampleInputs, String label, String description, String currentValue);


    Future<JsonObject> resolveRadioButtonAction(JsonArray state, String taskDescription, String htmlContext, String label, String description);

    Future<JsonObject> resolveSelectAction(JsonArray state, String taskDescription, String inputElementHTML, String htmlContext, String label, String description);

    Future<Boolean> resolveCheckboxAction(JsonObject state, String taskDescription, String label, String description);

}
