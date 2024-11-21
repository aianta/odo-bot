package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.snippet2xml.*;
import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class Snippet2XMLServiceImpl implements Snippet2XMLService {



    private AIStrategy strategy;

    public Snippet2XMLServiceImpl(JsonObject config, Strategy strategy){

        this.strategy = switch (strategy){
            case OPENAI -> new OpenAIStrategy(config);
        };

    }

    @Override
    public Future<SemanticObject> getObjectFromSnippet(Snippet snippet, SemanticSchema schema) {
        return null;
    }

    @Override
    public Future<JsonObject> makeSchema(List<Snippet> snippets) {

        return this.strategy.makeSchema(snippets);

    }



}
