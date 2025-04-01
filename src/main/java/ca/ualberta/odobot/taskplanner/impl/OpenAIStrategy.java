package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.taskplanner.AIStrategy;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private static Pattern listOfNumbers = Pattern.compile("^[0-9, ]+$", Pattern.MULTILINE);

    private static Pattern jsonResponsePattern = Pattern.compile("(?<=```json).+(?=```)", Pattern.DOTALL);

    public OpenAIStrategy(JsonObject config) {
        super(config);
    }


    public Future<String> selectPath(JsonObject paths, String taskDescription){
        log.info("Selecting from nav path options...");

        Optional<String> result = generateWithValidation(
                ()->_selectPath(paths, taskDescription),
                List.of((output)->{
                    try{
                        UUID.fromString(output);
                        return true;
                    }catch (IllegalArgumentException e){
                        return false;
                    }
                }),
                config.getJsonObject("selectPath").getInteger("maxAttempts")
                );

        if(result.isPresent()){
            Future.succeededFuture(UUID.fromString(result.get()).toString());
        }

        return Future.failedFuture("Failed to select a nav path!");

    }

    private String _selectPath(JsonObject paths, String taskDescription){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String prompt = config.getJsonObject("selectPath").getString("systemPrompt").formatted(taskDescription);
        chatMessages.add(new ChatRequestSystemMessage(prompt));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        Iterator<Map.Entry<String,Object>> it = paths.stream().iterator();
        while (it.hasNext()){
            Map.Entry<String, Object> entry = it.next();
            String navPathId = entry.getKey();
            JsonArray steps = (JsonArray)entry.getValue();
            sb.append("Path[id: %s]:\n".formatted(navPathId));

            //Write out all the steps in a path
            Iterator<String> stepIterator = steps.stream().map(o->(String)o).iterator();
            int stepNumber = 0;
            while (stepIterator.hasNext()){
                String currStep = stepIterator.next();
                stepNumber++;
                sb.append("\t%s. %s\n".formatted(Integer.toString(stepNumber), currStep));
            }

            sb.append("\n");

        }

        log.info("\n{}", sb.toString());
        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);
    }

    public Future<List<JsonObject>> getTaskAPICalls(String taskDescription, List<JsonObject> apiCalls){

        log.info("Getting relevant API calls from task descriptions:\n{}", taskDescription);

        Optional<String> result = generateWithValidation(()->_getTaskAPICalls(taskDescription, apiCalls), List.of((output)->listOfNumbers.asMatchPredicate().test(output)), config.getJsonObject("getTaskAPICalls").getInteger("maxAttempts"));

        if(result.isPresent()){
            List<JsonObject> chosenAPICalls = Arrays.stream(result.get().split(","))
                    .map(String::trim)
                    .mapToInt(Integer::parseInt)
                    .mapToObj(index->apiCalls.get(index - 1))
                    .collect(Collectors.toList());
            return Future.succeededFuture(chosenAPICalls);
        }

        return Future.failedFuture("Failed to get relevant API calls for task description!");

    }

    private String _getTaskAPICalls(String taskDescription, List<JsonObject> apiCalls) {
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("getTaskAPICalls").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        ListIterator<JsonObject> it = apiCalls.listIterator();
        while (it.hasNext()) {
            JsonObject curr = it.next();
            sb.append((it.previousIndex() + 1) + ". " + curr.getString("method") + " - " + curr.getString("path") + "\n");
        }
        sb.append("\n");
        sb.append("Task Description:\n");
        sb.append(taskDescription + "\n");

        log.info("\n{}", sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages)
                //Sometimes the LLM can't help itself but include the list in square brackets
                .replaceAll("\\[", "").replaceAll("\\]", "");
    }

    public Future<List<JsonObject>> getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations){

        log.info("Getting input parameter mappings from task description:\n{}", taskDescription);

        Optional<String> result = generateWithValidation(()->_getTaskInputParameterMappings(taskDescription, dataEntryAnnotations),
                //Validator attempts to parse output as JSON array
                List.of(
                        (output)->{
                            try{
                                JsonArray array = new JsonArray(output);
                                return true;
                            }catch (DecodeException e){
                                return false;
                            }
                        }
                ),
                config.getJsonObject("getInputParameterMappings").getInteger("maxAttempts")
                );

        if(result.isPresent()){
            JsonArray output = new JsonArray(result.get());
            List<JsonObject> mappedInputParameters = output.stream()
                    .map(o->(JsonArray)o)
                    .map(entry->{

                        //Exclude mappings to null.
                        if(entry.getValue(1) == null){

                            //Handle checkbox inputs differently, if they seem relevant, but no value can be attached to them, assume true.
                            JsonObject annotation = getAnnotationByLabel(entry.getString(0), dataEntryAnnotations);
                            if(annotation != null && annotation.getString("description").contains("checkbox")){
                                annotation.put("value", "true");
                                return annotation;
                            }

                            return null;
                        }

                        JsonObject associatedAnnotation = getAnnotationByLabel(entry.getString(0), dataEntryAnnotations);
                        associatedAnnotation.put("value", entry.getString(1));

                        return associatedAnnotation;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return Future.succeededFuture(mappedInputParameters);
        }

        return Future.failedFuture("Failed to get the input parameter mappings for the task description!");
    }

    private String extractJSONFromResponse (String input){
        if(input.contains("```json")){
            Matcher matcher = jsonResponsePattern.matcher(input);
            if(matcher.find()){
                return matcher.group(0);
            }
        }
        return input;
    }

    private JsonObject getAnnotationByLabel(String label, List<JsonObject> annotations){
        return annotations.stream().filter(annotation->annotation.getString("label").equals(label)).findFirst().get();
    }

    private String _getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("getInputParameterMappings").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        ListIterator<JsonObject> it = dataEntryAnnotations.listIterator();
        while (it.hasNext()){
            JsonObject curr = it.next();
            sb.append((it.previousIndex() + 1) + ". " + curr.getString("label") + "["+curr.getString("description")+"]\n");
        }
        sb.append("\n");
        sb.append("Task Description:\n");
        sb.append(taskDescription + "\n");

        log.info("\n{}", sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return extractJSONFromResponse(executeChatCompletion(chatMessages));
    }

    @Override
    public Future<List<JsonObject>> getTaskSchemas(String taskDescription, List<SemanticSchema> options) {

        log.info("Getting schemas from task description:\n{}", taskDescription);

        Optional<String> result = generateWithValidation(()->_getTaskSchemas(taskDescription, options),
                //Validator attempts to parse output as JSON array
                List.of(
                        (output)->{
                            try{
                                JsonArray array = new JsonArray(output);
                                return true;
                            }catch (DecodeException e){
                                return false;
                            }
                        }
                ),

                config.getJsonObject("getRelevantObjectParameters").getInteger("maxAttempts"));

        if(result.isPresent()){
            JsonArray output = new JsonArray(result.get());
            List<JsonObject> chosenSchemas = output.stream()
                    .map(o->(JsonArray)o)
                    .map(entry->{
                        //Exclude mappings to null
                        if(entry.getValue(1) == null){
                            return null;
                        }

                        try{
                            SemanticSchema associatedSchema = getSchemaByName(entry.getString(0), options);
                            JsonObject json = associatedSchema.toJson();
                            json.remove("schema");//Don't need that chunk of xml here.
                            json.put("query", entry.getString(1)); //Do need the query to use during runtime object parameter resolution.
                            return json;
                        }catch (NoSuchElementException e){
                            log.info("Tried to search for ({}) a schema that doesn't exist in the options list! ", entry.getString(0));
                            return null;
                        }

                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("Chose the following schemas:");
            chosenSchemas.forEach(s->log.info("{}", s.encodePrettily()));

            return Future.succeededFuture(chosenSchemas);
        }

        return Future.failedFuture("Failed to get relevant schemas for task description!");
    }

    private SemanticSchema getSchemaByName(String name, List<SemanticSchema> schemas){
        return schemas.stream().filter(schema->schema.getName().equals(name)).findFirst().get();
    }

    private String _getTaskSchemas(String taskDescription, List<SemanticSchema> options){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("getRelevantObjectParameters").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        ListIterator<SemanticSchema> it = options.listIterator();
        while (it.hasNext()){
            SemanticSchema curr = it.next();
            sb.append((it.previousIndex() + 1) + ". " + curr.getName() + "\n");
        }
        sb.append("\n");
        sb.append("Task Description:\n");
        sb.append(taskDescription + "\n");

        log.info("\n{}", sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return extractJSONFromResponse(executeChatCompletion(chatMessages));
    }


}
