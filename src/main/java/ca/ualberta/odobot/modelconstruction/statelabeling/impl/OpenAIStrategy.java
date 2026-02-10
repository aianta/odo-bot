package ca.ualberta.odobot.modelconstruction.statelabeling.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.modelconstruction.statelabeling.AIStrategy;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class OpenAIStrategy extends AbstractOpenAIStrategy implements AIStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStrategy.class);

    private int maxSnapshots = -1;

    public OpenAIStrategy(JsonObject config) {
        super(config);
        maxSnapshots = super.config.getJsonObject("generateStateClusterLabel").getInteger("maxSnapshots");
    }

    @Override
    public Future<JsonObject> generateStateLabeling(String clusterId, List<JsonObject> snapshots) {

        try{
            JsonObject result = new JsonObject()
                    .put("clusterId", clusterId)
                    .put("label", generateLabel(snapshots));

            //TODO: validation


            return Future.succeededFuture(result);
        }catch (Exception ex){
            log.error(ex.getMessage(), ex);
            return Future.failedFuture(ex);
        }
    }

    private String generateLabel(List<JsonObject> snapshots){
        List<ChatRequestMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatRequestSystemMessage(config.getJsonObject("generateStateClusterLabel").getString("systemPrompt")));

        StringBuilder sb = new StringBuilder();

        Iterator<JsonObject> it = snapshots.stream().limit(maxSnapshots).iterator();
        int count = 0;
        while (it.hasNext()) {
            count++;
            JsonObject item = it.next();
            String html = item.getString("snapshot");
            sb.append(count + ")\n");
            sb.append(html);

            if(it.hasNext()) {
                sb.append("-------------------------------------\n");
            }
        }

        chatMessages.add(new ChatRequestUserMessage(sb.toString()));

        return executeChatCompletion(chatMessages);


    }
}
