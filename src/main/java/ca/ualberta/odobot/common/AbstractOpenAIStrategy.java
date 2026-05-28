package ca.ualberta.odobot.common;

import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.*;
import com.azure.core.credential.KeyCredential;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class AbstractOpenAIStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIStrategy.class);

    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(6);
    protected OpenAIClient client;

    protected JsonObject config;

    protected String model; //The openAI model to use for chat completions

    public static TokenUsageRecord activeTokenUsageRecord;

    public String getModel(){
     return model;
    }

    public AbstractOpenAIStrategy(JsonObject config){
        this.config = config.getJsonObject("openAI");
        this.model = this.config.getString("model");

        client = new OpenAIClientBuilder()
                .credential(new KeyCredential(this.config.getString("secretKey")))
                .buildClient();


    }


    protected String executeChatCompletion(List<ChatRequestMessage> chatMessages){
        ChatCompletionsOptions options = new ChatCompletionsOptions(chatMessages);

        options.setN(1); //Only generate one choice
        options.setTemperature(config.getDouble("temperature"));
        if( config.containsKey("topP")){
            options.setTopP(config.getDouble("topP"));
        }
        if(config.containsKey("maxTokens")){
            options.setMaxTokens(config.getInteger("maxTokens"));
        }

        ChatCompletions chatCompletions = client.getChatCompletions(model, options);
        CompletionsUsage usage = chatCompletions.getUsage();

        //Record token usage if there is an active token usage record
        if(activeTokenUsageRecord != null){
            activeTokenUsageRecord.addInputTokens(usage.getPromptTokens());
            activeTokenUsageRecord.addOutputTokens(usage.getCompletionTokens());
            activeTokenUsageRecord.addTotalTokens(usage.getTotalTokens());
        }


        log.info("Got chat completion ({})@{}", chatCompletions.getId(), chatCompletions.getCreatedAt());
        ChatResponseMessage message = chatCompletions.getChoices().get(0).getMessage();
        String content = message.getContent();
        log.info("{}", content);

        return content;
    }

    /**
     * Helper method which executes an outputGenerator function up to maxAttempts times to produce output which passes all provided validators.
     * @param outputGenerator
     * @param validators
     * @param maxAttempts
     * @return An Optional containing a valid generated string output if one was generated, otherwise an empty optional
     */
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
