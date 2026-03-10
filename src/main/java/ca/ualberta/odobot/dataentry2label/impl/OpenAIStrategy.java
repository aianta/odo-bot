package ca.ualberta.odobot.dataentry2label.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.dataentry2label.AIStrategy;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ca.ualberta.odobot.common.AIOutputValidators.*;
import static ca.ualberta.odobot.taskplanner.impl.OpenAIStrategy.extractJSONFromResponse;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private Pattern labelResponsePattern = Pattern.compile("(?<=Label:( \\n|\\n)`).+(?=`\\n)");

    private Pattern descriptionResponsePattern = Pattern.compile("(?<=Description:( \\n|\\n)`).+(?=(`|`\\n))");

    private Pattern parenthesisPattern = Pattern.compile("\\(.+\\)");

    public OpenAIStrategy(JsonObject config) {
        super(config);
    }


    public JsonObject extractLabelAndDescriptionFromResponse(String input){

        JsonObject result = new JsonObject();

        Matcher labelMatcher = labelResponsePattern.matcher(input);
        labelMatcher.find();
        String label = labelMatcher.group(0);

        Matcher descriptionMatcher = descriptionResponsePattern.matcher(input);
        descriptionMatcher.find();
        String description = descriptionMatcher.group(0);

        result.put("label", label)
                .put("description", description);

        return result;
    }

    public Future<JsonArray> standardizeLabels(List<JsonObject> labels){

        log.info("Standardizing  {} data annotation labels", labels.size());

        //Set up validation
        List<Predicate<String>> validators = List.of(
                isValidJsonArray(),
                allJsonArrayObjectsHaveKeys("standard_name", "annotations"),
                allJsonArrayValuesMatchPredicate(arrayFieldValuesAreIntegersInRange("annotations", 1, labels.size()), JsonObject.class)
        );

        Optional<String> output = generateWithValidation(()->extractJSONFromResponse(_standardizeLabels(labels)), validators, config.getJsonObject("standardizeDataEntryLabels").getInteger("maxAttempts"));

        if(output != null && output.isPresent()){
            return Future.succeededFuture(new JsonArray(output.get()));
        }else{
            log.error("Failed to standardize data annotation labels");
            return Future.failedFuture("Failed to standardize data annotation labels");
        }

    }

    public String _standardizeLabels(List<JsonObject> labels){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("standardizeDataEntryLabels").getString("systemPrompt")));


        StringBuilder sb = new StringBuilder();
        sb.append("\nInput:\n");

        ListIterator<JsonObject> it = labels.listIterator();
        while (it.hasNext()) {
            JsonObject label = it.next();
            sb.append((it.previousIndex()+1) + ". " + label.getString("label") + "\n");
        }

        sb.append("\nOutput:\n");

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));



        return executeChatCompletion(chatMessages);


    }

    @Override
    public Future<JsonObject> generateLabelAndDescription(JsonObject input) {

        String xpath = input.getString("xpath");

        log.info("Generating label and description for data entry @{}", xpath);

        List<Predicate<String>> validators = List.of(
                (output)->labelResponsePattern.matcher(output).find(), //Ensure we're able to extract a label from the response.
                (output)->descriptionResponsePattern.matcher(output).find() //Ensure we're able to extract a description from the response.
        );

        Optional<String> output = generateWithValidation(()->generate(input), validators, config.getJsonObject("generateLabelAndDescription").getInteger("maxAttempts"));

        if(output != null && output.isPresent()){
            log.info("Got output {}", output.get());
            JsonObject result = extractLabelAndDescriptionFromResponse(output.get());
            result.put("label",result.getString("label").replaceAll(parenthesisPattern.pattern(), "").toLowerCase().trim());
            return Future.succeededFuture(result);
        }else{
            log.error("Failed to generate a label and description for data entry with xpath: {}", input.getString("xpath"));
            return Future.failedFuture("Failed to generate label and description");
        }

    }

    private String generate(JsonObject input){
        JsonArray exampleInputs = input.getJsonArray("enteredData");

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateLabelAndDescription").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("Input Element: %s\n".formatted(input.getString("inputElement")));
        sb.append("HTML Context: %s\n".formatted(input.getString("htmlContext")));

        if(exampleInputs.size() > 0){
            sb.append("Example Input Data: %s\n".formatted(exampleInputs.getString(0)));
        }

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);

    }


    protected Optional<String> generateWithValidation(Supplier<String> outputGenerator, List<Predicate<String>> validators, int maxAttempts){
        String output = outputGenerator.get();
        int attempt = 1;

        boolean isValid = validators.stream().allMatch(validator->validator.test(output));
        String _output = output;

        while (!isValid && attempt < maxAttempts){
            log.info("Attempt {} output was not valid, trying again...", attempt);
            String nextOutput = outputGenerator.get();
            isValid = validators.stream().allMatch(validator->validator.test(nextOutput));
            _output = nextOutput;
            attempt++;
        }

        return isValid?Optional.of(_output): Optional.empty();

    }

}
