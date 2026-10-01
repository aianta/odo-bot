package ca.ualberta.odobot.modelconstruction.linklabeling.impl;

import ca.ualberta.odobot.common.AbstractOpenAIStrategy;
import ca.ualberta.odobot.modelconstruction.linklabeling.LinkLabelingService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class LinkLabelingServiceImpl extends AbstractOpenAIStrategy implements LinkLabelingService{


    public LinkLabelingServiceImpl(JsonObject config) {
        super(config);
    }

    public Future<JsonObject> labelLink(String rawHref, String normalizedHref){

        String output = generateLabel(rawHref, normalizedHref);

        JsonObject jsonObject = new JsonObject()
                .put("rawHref", rawHref)
                .put("normalizedHref", normalizedHref);

        if(!output.equals("Does not link to a specific resource.")){
            jsonObject.put("type", output);
        }


        return Future.succeededFuture(jsonObject);

    }

    private String generateLabel(String rawHref, String normalizedHref){

        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(system(config.getJsonObject("generateLinkLabel").getString("systemPrompt")));

        String input = """
                Input: 
                raw href: %s normalized href: %s
                
                Output:
                """.formatted(rawHref, normalizedHref);
        chatMessages.add(system(input));

        return executeChatCompletion(chatMessages);

    }
}
