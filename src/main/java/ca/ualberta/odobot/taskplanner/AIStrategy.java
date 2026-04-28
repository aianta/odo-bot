package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {


    Future<List<JsonObject>> getTaskSchemas(String taskDescription, List<SemanticSchema> options);

    Future<List<JsonObject>> getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations);

    Future<List<JsonObject>> getTaskResourceParameters(String taskDescription, List<String> options);

    Future<List<JsonObject>> getTaskAPICalls(String taskDescription, List<JsonObject> apiCalls);

    Future<String> selectPath(JsonObject paths, String taskDescription);

    Future<String> resolveDataEntryValue(String taskDescription, String inputElementHTML, String htmlContext, List<String> exampleInputs, String label, String description, String currentValue);

    Future<String> resolveTextInputAction(JsonObject state, String taskDescription);

    Future<String> resolveTinyMCEAction(JsonObject state, String taskDescription);

    Future<JsonObject> resolveRadioButtonAction(JsonArray state, String taskDescription);

    Future<JsonObject> resolveSelectAction(JsonArray state, String taskDescription);

    Future<Boolean> resolveCheckboxAction(JsonObject state, String taskDescription);

}
