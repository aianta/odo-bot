package ca.ualberta.odobot.common;

import ca.ualberta.odobot.MainVerticle;
import ca.ualberta.odobot.guidance.TokenUsageRecord;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

public abstract class AbstractOpenAIStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIStrategy.class);

    //Clients are shared across strategy instances (keyed by api key + base url) since each one owns its own connection pool and threads.
    private static final Map<String, OpenAIClient> clients = new ConcurrentHashMap<>();

    private OpenAIClient client;

    protected JsonObject config;

    protected String model; //The openAI model to use for chat completions

    public enum Role {SYSTEM, USER}

    /**
     * SDK-neutral chat message, so subclasses don't depend on the OpenAI client library.
     */
    public record ChatMessage(Role role, String content){}

    protected static ChatMessage system(String content){
        return new ChatMessage(Role.SYSTEM, content);
    }

    protected static ChatMessage user(String content){
        return new ChatMessage(Role.USER, content);
    }

    public String getModel(){
     return model;
    }

    public AbstractOpenAIStrategy(JsonObject config){
        this.config = config.getJsonObject("openAI");
        if (MainVerticle.MODEL_OVERRIDE != null){
            this.model = MainVerticle.MODEL_OVERRIDE;
        }else{
            this.model = this.config.getString("model");
        }

        String apiKey = this.config.getString("secretKey");
        String baseUrl = this.config.getString("baseUrl"); //Optional, defaults to the OpenAI API
        client = clients.computeIfAbsent(apiKey + "@" + baseUrl, key->{
            OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
            if(baseUrl != null){
                builder.baseUrl(baseUrl);
            }
            return builder.build();
        });

    }


    protected String executeChatCompletion(List<ChatMessage> chatMessages){
        return executeChatCompletion(LlmCallType.OTHER_CHAT_COMPLETION, chatMessages);
    }

    /**
     * @param callType the context of this call, used to break down token usage.
     */
    protected String executeChatCompletion(LlmCallType callType, List<ChatMessage> chatMessages){
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(model)
                .n(1); //Only generate one choice

        //Sampling options are often absent or explicitly null in config, in which case they are left out of the request.
        Double temperature = config.getDouble("temperature");
        if(temperature != null){
            params.temperature(temperature);
        }
        Double topP = config.getDouble("topP");
        if(topP != null){
            params.topP(topP);
        }
        Integer maxTokens = config.getInteger("maxTokens");
        if(maxTokens != null){
            params.maxCompletionTokens(maxTokens);
        }

        for(ChatMessage message: chatMessages){
            switch (message.role()){
                case SYSTEM -> params.addSystemMessage(message.content());
                case USER -> params.addUserMessage(message.content());
            }
        }

        ChatCompletion chatCompletion = client.chat().completions().create(params.build());

        //Record token usage if there is an active token usage record
        chatCompletion.usage().ifPresent(usage->
                TokenUsageRecord.report(callType, usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));


        log.info("Got chat completion ({})@{}", chatCompletion.id(), chatCompletion.created());
        String content = chatCompletion.choices().get(0).message().content().orElse("");
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
