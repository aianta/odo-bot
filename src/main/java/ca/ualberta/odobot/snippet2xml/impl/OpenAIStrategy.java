package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.snippet2xml.AIStrategy;
import ca.ualberta.odobot.snippets.Snippet;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.*;
import com.azure.core.credential.KeyCredential;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenAIStrategy implements AIStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private Pattern xmlResponsePattern = Pattern.compile("(?<=```xml).+(?=```)", Pattern.DOTALL);

    private OpenAIClient client;
    private JsonObject config;

    private String model; //The openAI model to use for chat completions


    public OpenAIStrategy(JsonObject config){
        this.config = config.getJsonObject("openAI");
        this.model = this.config.getString("model");

        client = new OpenAIClientBuilder()
                .credential(new KeyCredential(config.getJsonObject("openAI").getString("secretKey")))
                .buildClient();

    }

    @Override
    public Future<JsonObject> makeSchema(List<Snippet> snippets) {

        Collections.shuffle(snippets);

        int samples = config.getJsonObject("makeSchema").getInteger("samples");

        Map<UUID, String> xmlObjects = new HashMap<>();
        List<Predicate<String>> validators = List.of(new IsValidXML());

        //First try and make some XML objects out of the given snippets.
        for(Snippet snippet: snippets){
            Optional<String> xmlObject = generateXMLObjectWithoutSchema(snippet, validators);
            if(xmlObject.isPresent()){
                xmlObjects.put(snippet.getId(), xmlObject.get());
            }

            if(xmlObjects.size() >= samples){
                break;
            }
        }

        //Handle case where the LLM simply fails to generate sufficient xml objects to generate an XML schema.
        if(xmlObjects.size() < samples){
            log.info("Could not generate sufficient ({}) xml objects to make schema. Generated {} from {} snippets.", samples, xmlObjects.size(), snippets.size());
            return Future.failedFuture("Failed to generate sufficient example xml objects for schema generation");
        }

        //At this point we have sufficient sample objects to try and generate an XML schema.
        log.info("Attempting to generate XML schema with the following {} XML objects:", xmlObjects.size());
        xmlObjects.values().stream().forEach(log::info);

        //TODO -> need to validate that the generated schema does in fact validate all example objects.
        Optional<String> schemaOptional = generateXMLSchema(xmlObjects.values(), List.of(new IsValidXML()));

        if(schemaOptional.isPresent()){
            return Future.succeededFuture(makeSchemaResponse(schemaOptional.get(), xmlObjects));
        }else{
            log.error("Failed to generate a schema using example xml objects.");
            return Future.failedFuture("Failed to generate a schema using example xml objects.");
        }
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

    /**
     * A predicate that tests if a string is valid XML.
     */
    private class IsValidXML implements Predicate<String> {

        private DocumentBuilder builder;

        IsValidXML(){
            try{
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                builder = factory.newDocumentBuilder();

            } catch (ParserConfigurationException e) {
                log.error("Error initializing DocumentBuilder");
                log.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }

        }

        @Override
        public boolean test(String string) {

            try{
                StringReader sr = new StringReader(string);
                InputSource is = new InputSource(sr);
                Document document = builder.parse(is);
                return true;
            }catch (SAXException parseError){
                return false;
            } catch (IOException e) {
                log.error("IO error while attempting to parse XML...");
                throw new RuntimeException(e);
            }

        }
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
    private Optional<String> generateWithValidation(Supplier<String> outputGenerator, List<Predicate<String>> validators, int maxAttempts){
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

    private String executeChatCompletion(List<ChatRequestMessage> chatMessages){
        ChatCompletionsOptions options = new ChatCompletionsOptions(chatMessages);
        options.setN(1); //Only generate one choice
        options.setTemperature(config.getDouble("temperature"));
        options.setTopP(config.getDouble("topP"));
        options.setMaxTokens(config.getInteger("maxTokens"));

        ChatCompletions chatCompletions = client.getChatCompletions(model, options);

        log.info("Got chat completion ({})@{}", chatCompletions.getId(), chatCompletions.getCreatedAt());
        ChatResponseMessage message = chatCompletions.getChoices().get(0).getMessage();
        String content = message.getContent();
        log.info("{}", content);

        return content;
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

    private Optional<String> generateXMLObjectWithoutSchema(Snippet snippet,  List<Predicate<String>> validators){
        return generateWithValidation(()->generateXMLObjectWithoutSchema(snippet), validators, config.getJsonObject("generateXMLObjectWithoutSchema").getInteger("maxAttempts"));
    }

    /**
     *
     * https://github.com/Azure/azure-sdk-for-java/tree/main/sdk/openai/azure-ai-openai#chat-completions
     *
     * @param snippet
     * @return
     */
    private String generateXMLObjectWithoutSchema(Snippet snippet){

        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateXMLObjectWithoutSchema").getString("systemPrompt")));
        chatMessages.add(new ChatRequestUserMessage(snippet.getSnippet()));

        return executeChatCompletion(chatMessages);
    }
}
