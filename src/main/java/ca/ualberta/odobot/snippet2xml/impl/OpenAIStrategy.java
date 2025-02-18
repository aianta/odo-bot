package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.snippet2xml.AIStrategy;
import ca.ualberta.odobot.snippet2xml.SemanticObject;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.snippet2xml.impl.validators.IsValidXML;
import ca.ualberta.odobot.snippet2xml.impl.validators.PassesSchemaValidation;
import ca.ualberta.odobot.snippet2xml.impl.validators.SchemaValidatesXMLObjects;
import ca.ualberta.odobot.snippets.Snippet;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.*;
import com.azure.core.credential.KeyCredential;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private Pattern xmlResponsePattern = Pattern.compile("(?<=```xml).+(?=```)", Pattern.DOTALL);

    public OpenAIStrategy(JsonObject config) {
        super(config);
    }


    @Override
    public Future<JsonObject> makeSchema(List<Snippet> snippets) {

        Collections.shuffle(snippets);

        int samples = config.getJsonObject("makeSchema").getInteger("samples");

        Map<UUID, String> xmlObjects = new HashMap<>();
        List<Predicate<String>> validators = List.of(new IsValidXML());

        //First try and make some XML objects out of the given snippets.
        for(Snippet snippet: snippets){
            /**
             * Feed successful outputs to subsequent chat completion requests in order to normalize/make more consistent objects.
             */
            Optional<String> xmlObject = xmlObjects.size() == 0? generateXMLObjectWithoutSchema(snippet, validators) : generateXMLObjectWithoutSchema(snippet, xmlObjects.values(), validators);
            if(xmlObject.isPresent()){
                xmlObjects.put(snippet.getId(), xmlObject.get());
            }

//            if(xmlObjects.size() >= samples){
//                break;
//            }
        }

        //Handle case where the LLM simply fails to generate sufficient xml objects to generate an XML schema.
        if(xmlObjects.size() < samples){
            log.info("Could not generate sufficient ({}) xml objects to make schema. Generated {} from {} snippets.", samples, xmlObjects.size(), snippets.size());
            return Future.failedFuture("Failed to generate sufficient example xml objects for schema generation");
        }

        /**
         * We got more snippets than required, so if we fail to generate a schema, we can try again with another batch.
         * Basically, we keep trying to generate schemas with different samples if we fail off any single batch.
         */
        Set<Map.Entry<UUID,String>> roster = xmlObjects.entrySet();
        Iterator<Map.Entry<UUID,String>> it = roster.iterator();
        Map<UUID,String> batch = new HashMap<>();
        Optional<String> schemaOptional = null;
        while (it.hasNext()){
            Map.Entry<UUID,String> entry = it.next();
            batch.put(entry.getKey(), entry.getValue());

            if(batch.size() >= samples || (!it.hasNext() && batch.size() != 0)){ //Try and generate a schema if the batch size has reached the target number of samples or if there are no xmlobjects left and there is an untried batch left.

                //At this point we have sufficient sample objects to try and generate an XML schema.
                log.info("Attempting to generate XML schema with the following {} XML objects:", batch.size());
                batch.values().stream().forEach(log::info);

                //TODO: January 16 2025: for candidacy evaluation, and for now, I have disabled the validator that ensures the schema validates the given XML objects. Will probably have to adjust this if we want to start using the XML objects more formally.
                //schemaOptional = generateXMLSchema(batch.values(), List.of(new IsValidXML(), new SchemaValidatesXMLObjects(batch.values())));
                schemaOptional = generateXMLSchema(batch.values(), List.of(new IsValidXML()));

                if(schemaOptional.isPresent()){
                    //If  a valid schema was generated, break out of the batching loop.
                    break;
                }

                batch.clear();//Reset the batch
            }
        }




        if(schemaOptional != null && schemaOptional.isPresent()){
            return Future.succeededFuture(makeSchemaResponse(schemaOptional.get(), batch));
        }else{
            log.error("Failed to generate a schema using example xml objects.");
            return Future.failedFuture("Failed to generate a schema using example xml objects.");
        }
    }

    public Future<SemanticObject> pickParameterValue(List<SemanticObject> options, String query){

        //Only validator we need is one that makes sure that the output is a valid integer.
        List<Predicate<String>> validators = List.of((input)->{
            log.info("Validating input: {}", input);
            try{
                Integer.parseInt(input);
                log.info("Input is fine");
                return true;
            }catch (NumberFormatException e){
                log.info("Input is not fine");
                return false;
            }
        });

        Optional<String> pickedValue = pickParameter(options, query, validators);
        if(pickedValue.isPresent()){
            Integer index = Integer.parseInt(pickedValue.get());
            //Subtract 1 from the picked option to get the correct index into the options array.
            return Future.succeededFuture(options.get(index - 1));
        }

        return Future.failedFuture("Failed to pick a parameter value option from the list!");
    }

    public Optional<String> pickParameter(List<SemanticObject> options, String query, List<Predicate<String>> validators){
        return generateWithValidation(()->pickParameter(options, query), validators, config.getJsonObject("pickParameterValue").getInteger("maxAttempts"));
    }

    @Override
    public Future<SemanticObject> makeObject(Snippet snippet, SemanticSchema schema) {

        List<Predicate<String>> validators = List.of(new PassesSchemaValidation(schema.getSchema()));

        Optional<String> xmlObject = generateXMLObject(snippet, schema, validators);
        if(xmlObject.isPresent()){
            SemanticObject result = new SemanticObject();
            result.setObject(xmlObject.get());
            result.setSchemaId(schema.getId());
            result.setSnippetId(snippet.getId());
            result.setId(UUID.randomUUID());

            return Future.succeededFuture(result);
        }


        return Future.failedFuture("Failed to generate semantic object for snippet: %s and schema: %s".formatted(snippet.getId().toString(), schema.getId().toString()));
    }

    @Override
    public Future<SemanticObject> makeObjectIgnoreSchemaIssues(String html, SemanticSchema schema) {
        List<Predicate<String>> validators = List.of();

        Optional<String> xmlObject = generateXMLObject(html, schema, validators);
        if(xmlObject.isPresent()){
            SemanticObject result = new SemanticObject();
            result.setObject(xmlObject.get());
            result.setSchemaId(schema.getId());
            result.setSnippetId(null); //TODO -> we should probably be saving these snippets, they would likely make decent examples to continue enhancing the nav model.
            result.setId(UUID.randomUUID());

            return Future.succeededFuture(result);
        }

        return Future.failedFuture("Failed to generate semantic object for HTML!");
    }

    @Override
    public Future<SemanticObject> makeObject(String html, SemanticSchema schema) {
        List<Predicate<String>> validators = List.of(new PassesSchemaValidation(schema.getSchema()));

        Optional<String> xmlObject = generateXMLObject(html, schema, validators);
        if(xmlObject.isPresent()){
            SemanticObject result = new SemanticObject();
            result.setObject(xmlObject.get());
            result.setSchemaId(schema.getId());
            result.setSnippetId(null); //TODO -> we should probably be saving these snippets, they would likely make decent examples to continue enhancing the nav model.
            result.setId(UUID.randomUUID());

            return Future.succeededFuture(result);
        }

        return Future.failedFuture("Failed to generate semantic object for HTML!");
    }

    /**
     * It is possible that the response from the agent will be wrapped in a markdown xml code block. This method uses a regex to extract the XML inside.
     * @param input
     * @return
     */
    private String extractXMLFromResponse(String input){

        //Only try to extract xml if it appears that xml is included inside a code block.
        if(input.contains("```xml")){

            Matcher matcher = xmlResponsePattern.matcher(input);
            if(matcher.find()){
                return matcher.group(0);
            }
        }

        return input;
    }

    private JsonObject makeSchemaResponse(String schema, Map<UUID, String> xmlObjects){
        JsonObject response = new JsonObject();
        response.put("schema", schema);
        xmlObjects.entrySet().forEach(entry->response.put(entry.getKey().toString(), entry.getValue()));

        return response;
    }





    private Optional<String> generateXMLSchema(Collection<String> xmlObjects, List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLSchema(xmlObjects), validators, config.getJsonObject("generateXMLSchema").getInteger("maxAttempts"));
    }

    /**
     * Helper method which executes an outputGenerator function up to maxAttempts times to produce output which passes all provided validators.
     * @param outputGenerator
     * @param validators
     * @param maxAttempts
     * @return An Optional containing a valid generated string output if one was generated, otherwise an empty optional
     */
    protected Optional<String> generateWithValidation(Supplier<String> outputGenerator, List<Predicate<String>> validators, int maxAttempts){
        String output = extractXMLFromResponse(outputGenerator.get());
        int attempt = 1;

        boolean isValid = validators.stream().allMatch(validator->validator.test(output));
        String _output = output;

        //Where there are validators which are unsatisfied with the output and the maximum number of attempts hasn't been reached.
        while(!isValid && attempt < maxAttempts){
            log.info("Attempt {} output was not valid, trying again...", attempt);
            String nextOutput = extractXMLFromResponse(outputGenerator.get());
            isValid = validators.stream().allMatch(validator->validator.test(nextOutput));
            _output = nextOutput;
            attempt++;
        }

        //If the output is valid wrap it in an optional and return it.
        return isValid? Optional.of(_output): Optional.empty();
        //return Optional.of(_output); //TODO -> the above line of code is the correct one, this line simply sends back the last attempt no matter waht.
    }


    private String generateXMLSchema(Collection<String> xmlObjects){

        int samples = xmlObjects.size();

        assert samples == config.getJsonObject("makeSchema").getInteger("samples");

        String systemPrompt = config.getJsonObject("generateXMLSchema").getString("systemPrompt")
                .formatted(Integer.toString(samples));

        String xmlObjectExamples = buildXMLExamplesMessageForMakeSchema(xmlObjects);

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(systemPrompt));
        chatMessages.add(new ChatRequestUserMessage(xmlObjectExamples));

        return executeChatCompletion(chatMessages);
    }


    /**
     * Returns a string formatted as follows:
     * <pre>
     * 1.
     * [XML Object 1]
     *
     * 2.
     * [XML Object 2]
     *
     * 3.
     * [XML Object 3]
     *
     * ...
     * </pre>
     *
     * @param xmlObjects A collection of XML snippets/objects.
     * @return
     */
    private String buildXMLExamplesMessageForMakeSchema(Collection<String> xmlObjects){

        int samples = xmlObjects.size();

        StringBuilder sb = new StringBuilder();

        Iterator<String> it = xmlObjects.iterator();
        int index = 0;
        while (it.hasNext()){
            assert index < samples;

            sb.append((index+1) + ".\n");
            sb.append(it.next() + "\n");
            index++;
        }

        return sb.toString();
    }

    private Optional<String> generateXMLObjectWithoutSchema(Snippet snippet, Collection<String> seedObjects, List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLObjectWithoutSchema(snippet, seedObjects), validators, config.getJsonObject("generateXMLObjectWithoutSchema").getInteger("maxAttempts"));
    }

    private Optional<String> generateXMLObjectWithoutSchema(Snippet snippet,  List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLObjectWithoutSchema(snippet, (List<String>)null), validators, config.getJsonObject("generateXMLObjectWithoutSchema").getInteger("maxAttempts"));
    }

    private Optional<String> generateXMLObject(String snippet, SemanticSchema schema, List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLObject(snippet, schema.getSchema()),validators, config.getJsonObject("generateXMLObject").getInteger("maxAttempts"));
    }
    private Optional<String> generateXMLObject(Snippet snippet, SemanticSchema schema, List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLObject(snippet.getSnippet(), schema.getSchema()), validators, config.getJsonObject("generateXMLObject").getInteger("maxAttempts"));
    }

    private String pickParameter(List<SemanticObject> options, String query){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("pickParameterValue").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("Query:\n");
        sb.append(query + "\n");
        sb.append("Options:\n");

        List<String> optionStrings = options.stream().map(SemanticObject::getObject).collect(Collectors.toList());
        sb.append(buildXMLExamplesMessageForMakeSchema(optionStrings));

        log.info("pick param prompt segment: \n{}", sb.toString());

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);

    }

    private String generateXMLObject(String snippet, String schema){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateXMLObject").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();
        sb.append("Schema:\n");
        sb.append(schema + "\n");
        sb.append("HTML Snippet:\n");
        sb.append(snippet);

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);

    }

    /**
     *
     * https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/openai/azure-ai-openai#chat-completions
     *
     * @param snippet
     * @return
     */
    private String generateXMLObjectWithoutSchema(Snippet snippet, Collection<String> seedObjects){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateXMLObjectWithoutSchema").getString("systemPrompt")));

        if(seedObjects == null || seedObjects.size() == 0){
            //If no seed objects are provided, simply pass the HTML snippet as the user message and execute the chat completion request.
            chatMessages.add(new ChatRequestUserMessage(snippet.getSnippet()));

        }else{
            //If seed XML objects are provided we constructed a prompt with them included first and then the HTML snippet.
            StringBuilder sb = new StringBuilder();
            sb.append("XML Examples:\n");
            sb.append(buildXMLExamplesMessageForMakeSchema(seedObjects));

            //If the snippet URL is provided, include it in the prompt.
            if(snippet.getBaseURI() != null){
                sb.append("Snippet URL:\n");
                sb.append(snippet.getBaseURI());
            }

            sb.append("HTML Snippet:\n");
            sb.append(snippet.getSnippet());

            chatMessages.add(new ChatRequestUserMessage(sb.toString()));
        }

        return executeChatCompletion(chatMessages);

    }
}
