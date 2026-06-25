package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.common.AIOutputValidators;
import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.common.UsageTelemetry;
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

import static ca.ualberta.odobot.common.AIOutputValidators.isNumber;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy, UsageTelemetry {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private static Pattern listOfNumbers = Pattern.compile("^[0-9, ]+$", Pattern.MULTILINE);

    private static Pattern jsonResponsePattern = Pattern.compile("(?<=```json).+(?=```)", Pattern.DOTALL);

    public OpenAIStrategy(JsonObject config) {
        super(config);
    }


    @Override
    public Future<String> resolveDataEntryValue(String taskDescription, String inputElementHTML, String htmlContext, List<String> exampleInputs, String label, String description, String currentValue) {

        Optional<String> result = generateWithValidation(
                ()->_generateInputValue(taskDescription, inputElementHTML, htmlContext, exampleInputs, label, description, currentValue),
                List.of(),
                config.getJsonObject("resolveDataEntryValue").getInteger("maxAttempts")
        );

        if(result.isPresent()) {
            return Future.succeededFuture(result.get());
        }

        return Future.failedFuture("Failed to generate data entry input value.");
    }


    @Override
    public Future<JsonObject> resolveRadioButtonAction(JsonArray state, String taskDescription, String htmlContext, String label, String description) {

        Optional<String> result = generateWithValidation(
                ()->_generateRadioValue(state, taskDescription, htmlContext, label, description),
                List.of(),
                config.getJsonObject("resolveRadioValue").getInteger("maxAttempts")
        );

        if(result.isPresent()) {
            Optional<JsonObject> chosenButton = state.stream().map(JsonObject.class::cast)
                    .filter(button->button.getString("value").equals(result.get()))
                    .findFirst();

            if(chosenButton.isPresent()){
                return Future.succeededFuture(chosenButton.get());
            }else {
                return Future.failedFuture("Generated value (%s) did not match any of the available radio button options in the state!".formatted(result.get()));
            }
        }else {
            return Future.failedFuture("Failed to generate radio button value.");
        }

    }

    @Override
    public Future<JsonObject> resolveSelectAction(JsonArray state, String taskDescription, String inputElementHTML, String htmlContext, String label, String description) {

        Optional<String> result = generateWithValidation(
                ()->_generateSelectValue(state, taskDescription, inputElementHTML, htmlContext, label, description),
                List.of(),
                config.getJsonObject("resolveSelectOption").getInteger("maxAttempts")
        );

        if(result.isPresent()) {
            JsonObject selectState = state.getJsonObject(0);

            Optional<JsonObject> chosenOption = selectState.getJsonArray("options").stream().map(JsonObject.class::cast)
                    .filter(option->option.getString("value").equals(result.get()))
                    .findFirst();

            if(chosenOption.isPresent()){
                return Future.succeededFuture(chosenOption.get());
            }else{
                return Future.failedFuture("Failed to find the chosen select option (%s) in the state!".formatted(result.get()));
            }
        }else{
            return Future.failedFuture("Failed to generate select option value.");
        }

    }

    private String _generateRadioValue(JsonArray state, String taskDescription, String htmlContext, String label, String description){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String prompt = config.getJsonObject("resolveRadioValue").getString("systemPrompt");
        chatMessages.add(new ChatRequestSystemMessage(prompt));

        StringBuilder sb = new StringBuilder();
        sb.append("\nRadio Button Element Information:\n");
        sb.append("\tInferred Semantic Label: %s\n".formatted(label));
        sb.append("\tInferred Semantic Description:\n%s\n".formatted(description));
        //sb.append("\tRadio Buttons:\n%s\n".formatted(htmlContext));
        sb.append("\n\n");
        sb.append("Task Description:\n%s\n".formatted(taskDescription));


        Iterator<JsonObject> it = state.stream().map(JsonObject.class::cast).iterator();

        while (it.hasNext()){
            JsonObject button = it.next();
            sb.append("'%s'".formatted(button.getString("value")));
            if(it.hasNext()){
                sb.append(", ");
            }
        }

        sb.append("Available Radio Button Values:\n[%s]\n".formatted(sb.toString()));

        log.info("{}", prompt + sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));
        return executeChatCompletion(chatMessages);
    }

    private String _generateSelectValue(JsonArray state, String taskDescription, String inputElementHTML, String htmlContext, String label, String description){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String prompt = config.getJsonObject("resolveSelectOption").getString("systemPrompt");
        chatMessages.add(new ChatRequestSystemMessage(prompt));

        StringBuilder sb = new StringBuilder();
        sb.append("\nSelect Element Information:\n");
        sb.append("\tField HTML Element: %s\n".formatted(inputElementHTML));
        sb.append("\tSurrounding HTML Context:\n%s\n".formatted(htmlContext));
        sb.append("\tInferred Semantic Label: %s\n".formatted(label));
        sb.append("\tInferred Semantic Description:\n%s\n".formatted(description));
        sb.append("\n\n");
        sb.append("Task Description:\n%s\n".formatted(taskDescription));
        sb.append("Available Options:\n");

        JsonObject selectState = state.getJsonObject(0);
        Iterator<JsonObject> it = selectState.getJsonArray("options").stream().map(JsonObject.class::cast).iterator();
        while (it.hasNext()){
            JsonObject option = it.next();
            sb.append("\tOption Value: %s, Option Label: %s\n".formatted(option.getString("value"), option.getString("text")));
        }
        sb.append("\n");
        sb.append("Output:\n");

        log.info("{}", prompt + sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);

    }

    @Override
    public Future<Boolean> resolveCheckboxAction(JsonObject state, String taskDescription) {
        return null;
    }

    public Future<String> generateNodeAnnotation(List<String> descriptions){

        int numDescriptions =config.getJsonObject("generateNodeAnnotation").getInteger("sampledDescriptions");

        descriptions = descriptions.stream().limit(numDescriptions).collect(Collectors.toList());


        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String systemPrompt = config.getJsonObject("generateNodeAnnotation").getString("systemPrompt")
                .formatted(descriptions.size(), descriptions.size());
        chatMessages.add(new ChatRequestSystemMessage(systemPrompt));

        StringBuilder sb = new StringBuilder();
        sb.append("Descriptions for this step:\n");
        Iterator<String> it = descriptions.iterator();
        while (it.hasNext()){
            String curr = it.next();
            sb.append("\t* %s\n".formatted(curr));
        }
        sb.append("\n");
        sb.append("Generated Instruction:\n");
        log.info("{}", systemPrompt + sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return Future.succeededFuture(executeChatCompletion(chatMessages));

    }

    public Future<JsonObject> pickMostRelevantTask(String queryTask, List<JsonObject> options){

        Optional<String> result = generateWithValidation(
                ()->_pickMostRelevantTask(queryTask, options),
                List.of(isNumber),
                config.getJsonObject("selectMostRelevantTask").getInteger("maxAttempts")
        );

        if(result.isPresent()){
            int chosenOption = Integer.parseInt(result.get()) - 1;

            return Future.succeededFuture(options.get(chosenOption));
        }else {
            return Future.failedFuture("Failed to select the most relevant task.");
        }

    }

    public String _pickMostRelevantTask(String queryTask, List<JsonObject> options){
        List<ChatRequestMessage> chatRequestMessages = new ArrayList<>();
        String prompt = config.getJsonObject("selectMostRelevantTask").getString("systemPrompt");
        chatRequestMessages.add(new ChatRequestSystemMessage(prompt));

        ListIterator<JsonObject> it = options.listIterator();
        StringBuilder sb = new StringBuilder();
        sb.append("\nKnown similar high-level tasks:\n");
        while (it.hasNext()){
            JsonObject option = it.next();
            sb.append("%d. %s\n".formatted(it.previousIndex()+1, option.getString("task")));
        }

        sb.append("\n");
        sb.append("Given task:\n");
        sb.append("%s\n".formatted(queryTask));

        chatRequestMessages.add(new ChatRequestUserMessage(sb.toString()));

        log.info("{}", prompt + sb.toString());

        return executeChatCompletion(chatRequestMessages);

    }


    public Future<String> rewriteQueryTaskWithoutSpecificInputs(String queryTask, List<JsonObject> syntheticTasks){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String prompt = config.getJsonObject("rewriteQueryTaskWithoutSpecificInputs").getString("systemPrompt");

        StringBuilder sb = new StringBuilder();
        Collections.shuffle(syntheticTasks);
        int numExamples = config.getJsonObject("rewriteQueryTaskWithoutSpecificInputs").getInteger("numExamples");

        syntheticTasks
                .stream()
                .limit(numExamples)
                .forEach(example->{
                    sb.append("\t* %s\n".formatted(example.getString("task")));
                });

        prompt = prompt.formatted(sb.toString());
        chatMessages.add(new ChatRequestSystemMessage(prompt));

        String userMessage = """
      The current task to rewrite:
      %s
      
      Re-written task:
      """.formatted(queryTask);


        log.info("{}", prompt + userMessage);
        chatMessages.add(new ChatRequestUserMessage(userMessage));

        return Future.succeededFuture(executeChatCompletion(chatMessages));
    }


    public Future<JsonObject> selectPath(JsonObject paths, String taskDescription){
        log.info("Selecting from nav path options...");

        JsonObject telemetry = new JsonObject();
        Optional<String> result = generateWithValidation(
                ()->_selectPath(paths, taskDescription, telemetry),
                List.of((output)->{
                    try{
                        UUID uuid = UUID.fromString(output);
                        Set<UUID> validOptions  = paths.stream().map(Map.Entry::getKey).map(UUID::fromString).collect(Collectors.toSet());
                        return validOptions.contains(uuid);
                        //return true;
                    }catch (IllegalArgumentException e){
                        return false;
                    }
                }),
                config.getJsonObject("selectPath").getInteger("maxAttempts")
                );

        if(result.isPresent()){
            telemetry.put("chosenPathId",result.get());
            return Future.succeededFuture(telemetry);
        }

        log.error("{}", telemetry.encodePrettily());
        return Future.failedFuture("Failed to select a nav path!");

    }

    private String _generateInputValue(String taskDescription, String inputElementHTML, String htmlContext, List<String> exampleInputs, String label, String description, String currentValue){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        String prompt = config.getJsonObject("resolveDataEntryValue").getString("systemPrompt");
        chatMessages.add(new ChatRequestSystemMessage(prompt));

        StringBuilder sb = new StringBuilder();
        sb.append("\nField Information:\n");
        sb.append("\tField HTML Element: %s\n".formatted(inputElementHTML));
        sb.append("\tSurrounding HTML Context:\n%s\n".formatted(htmlContext));
        if(!exampleInputs.isEmpty()){
            sb.append("\tExample Inputs: %s\n".formatted(exampleInputs.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encode()));
        }
        sb.append("\tInferred Semantic Label: %s\n".formatted(label));
        sb.append("\tInferred Semantic Description:\n%s\n".formatted(description));
        sb.append("\n\n");
        sb.append("Task Description:\n%s\n".formatted(taskDescription));
        sb.append("Existing/Current Value:\n'%s'\n".formatted(currentValue));
        sb.append("Output:\n");

        log.info("{}", prompt + sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);
    }


    private String _selectPath(JsonObject paths, String taskDescription, JsonObject telemetry){
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

        telemetry.put("prompt", prompt + sb.toString());

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
            String genericEntry = (it.previousIndex() + 1) + ". [%s]";
            String methodAndPath =  curr.getString("method") + " - " + curr.getString("path");

            if(curr.containsKey("operationName")){
                genericEntry = genericEntry.formatted("GraphQL");
                sb.append(genericEntry + " " +  curr.getString("operationName") + "\n");
            }else{
                genericEntry = genericEntry.formatted("REST API");
                sb.append(genericEntry + " " +  methodAndPath + "\n");
            }

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
                        AIOutputValidators.isValidJsonArray()
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

    public static String extractJSONFromResponse (String input){
        if(input.contains("```json")){
            Matcher matcher = jsonResponsePattern.matcher(input);
            if(matcher.find()){
                return matcher.group(0);
            }
        }
        return input;
    }

    private JsonObject getAnnotationByLabel(String label, List<JsonObject> annotations){
        Optional<JsonObject> _annotation = annotations.stream().filter(annotation->annotation.getString("label").equals(label)).findFirst();
        if(_annotation.isEmpty()){
            _annotation = annotations.stream()
                    .filter(annotation->annotation.containsKey("radioGroup"))
                    .filter(annotation->annotation.getString("radioGroup").equals(label)).findFirst();
        }

        return _annotation.get();
    }

    private String _getTaskInputParameterMappings(String taskDescription, List<JsonObject> dataEntryAnnotations){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("getInputParameterMappings").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        ListIterator<JsonObject> it = dataEntryAnnotations.listIterator();
        while (it.hasNext()){
            JsonObject curr = it.next();
//            if (curr.containsKey("radioGroup")){
//                //Use the radio group name for radio button inputs.
//                sb.append((it.previousIndex() + 1) + ". " + curr.getString("radioGroup") + "["+curr.getString("description")+"]\n");
//            }else{
//
//            }

            sb.append((it.previousIndex() + 1) + ". " + curr.getString("label") + "["+curr.getString("description")+"]\n");

        }
        sb.append("\n");
        sb.append("Task Description:\n");
        sb.append(taskDescription + "\n");

        log.info("\n{}", sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return extractJSONFromResponse(executeChatCompletion(chatMessages));
    }

    public Future<List<JsonObject>> getTaskResourceParameters(String taskDescription, List<String> options){
        log.info("Getting resource parameters from task description:\n{}", taskDescription);

        Optional<String> result = generateWithValidation(()->_getTaskResourceParameters(taskDescription, options),
                List.of(
                        (output)->{
                            try{
                                JsonArray array = new JsonArray(output);
                                return true;
                            }catch (DecodeException e){
                                return false;
                            }
                        }
                ), config.getJsonObject("getRelevantResourceParameters").getInteger("maxAttempts")
                );

        if(result.isPresent()){
            JsonArray output = new JsonArray(result.get());
            log.info("{}", output.encodePrettily());

            List<JsonObject> mappedResourceParameters = output.stream()
                    .map(o->(JsonArray)o)
                    .map(entry->{
                        //Exclude Mappings to null
                        if(entry.getValue(1) == null){
                            return null;
                        }

                        String chosenOption = options.stream().filter(option->option.equals(entry.getString(0))).findFirst().get();
                        if(chosenOption == null){
                            return null;
                        }

                        JsonObject returnValue = new JsonObject()
                                .put("name", entry.getString(0))
                                .put("query", entry.getString(1));

                        return returnValue;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("Mapped Resource Parameters:\n");
            mappedResourceParameters.forEach(mappedParameter->{
                log.info("{}\n", mappedParameter.encodePrettily());
            });



            return Future.succeededFuture(mappedResourceParameters);
        }

        return Future.failedFuture("Failed to get the resource parameters for the task description!");
    }

    private String _getTaskResourceParameters(String taskDescription, List<String> options){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("getRelevantResourceParameters").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        ListIterator<String> it = options.listIterator();
        while (it.hasNext()){
            String curr = it.next();
            sb.append((it.previousIndex() + 1) + ". " + curr + "\n");
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
