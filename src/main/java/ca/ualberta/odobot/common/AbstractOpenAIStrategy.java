package ca.ualberta.odobot.common;

import ca.ualberta.odobot.dataentry2label.impl.OpenAIStrategy;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.core.credential.KeyCredential;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class AbstractOpenAIStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIStrategy.class);

    protected OpenAIClient client;

    protected JsonObject config;

    protected String model; //The openAI model to use for chat completions

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
     * Helper method which executes an outputGenerator function up to maxAttempts times to produce output which passes all provided validators.
     * @param outputGenerator
     * @param validators
     * @param maxAttempts
     * @return An Optional containing a valid generated string output if one was generated, otherwise an empty optional
     */
    protected abstract Optional<String> generateWithValidation(Supplier<String> outputGenerator, List<Predicate<String>> validators, int maxAttempts);

}
