package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.snippet2xml.*;
import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;


import java.util.List;


public class Snippet2XMLServiceImpl implements Snippet2XMLService {


    private Vertx vertx;
    private AIStrategy strategy;

    public Snippet2XMLServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this.vertx = vertx;
        this.strategy = switch (strategy){
            case OPENAI -> new OpenAIStrategy(config);
        };

    }



    @Override
    public Future<SemanticObject> getObjectFromSnippet(Snippet snippet, SemanticSchema schema) {

        return vertx.<SemanticObject>executeBlocking(blocking->{
            this.strategy.makeObject(snippet, schema)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
            ;
        });

    }

    @Override
    public Future<SemanticObject> getObjectFromHTML(String html, SemanticSchema schema) {
        return vertx.executeBlocking(blocking->{
            this.strategy.makeObject(html, schema)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }

    @Override
    public Future<JsonObject> makeSchema(List<Snippet> snippets) {

        assert snippets.get(0).getDynamicXpath() != null;

        return vertx.<JsonObject>executeBlocking(blocking->{

            this.strategy.makeSchema(snippets)
                    //Inject the dynamic xpath used to sample the snippets into the makeSchema result.
                    .compose(result->Future.succeededFuture(result.put("dynamicXpath", snippets.get(0).getDynamicXpath())))
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
            ;
        });

    }

    @Override
    public Future<SemanticObject> pickParameterValue(List<SemanticObject> options, String query) {
        return vertx.executeBlocking(blocking->{
            this.strategy.pickParameterValue(options, query)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }


}
