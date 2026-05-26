package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.common.UsageTelemetry;
import ca.ualberta.odobot.guidance.RequestManager;
import ca.ualberta.odobot.snippet2xml.*;
import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.List;

import static ca.ualberta.odobot.guidance.RequestManager.tokenUsageRecord;


public class Snippet2XMLServiceImpl implements Snippet2XMLService {

    private static final Logger log = LoggerFactory.getLogger(Snippet2XMLServiceImpl.class);
    private Vertx vertx;
    private AIStrategy strategy;
    public static String model;

    public Snippet2XMLServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this.vertx = vertx;
        this.strategy = switch (strategy){
            case OPENAI -> {
                OpenAIStrategy _strategy = new OpenAIStrategy(config);
                RequestManager.tokenUsageRecordListeners.add(_strategy::onNewTokenUsageRecord);
                model = _strategy.getModel();
                yield _strategy;
            }
        };



    }

    public String getModel(){
        return ((UsageTelemetry)strategy).getModel();
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
    public Future<SemanticObject> getObjectFromHTMLIgnoreSchemaIssues(String html, SemanticSchema schema) {
        return vertx.executeBlocking(blocking->{
            this.strategy.makeObjectIgnoreSchemaIssues(html, schema)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }

    @Override
    public Future<JsonObject> makeSchema(List<Snippet> snippets) {

        assert snippets.get(0).getDynamicXpath() != null;

        log.info("Making schema from samples:");
        snippets.forEach(snippet -> log.info("{}", snippet.getSnippet().substring(0, Math.min(snippet.getSnippet().length(), 200))));


        return vertx.<JsonObject>executeBlocking(blocking->{

            this.strategy.makeSchema(snippets)
                    //Inject the dynamic xpath used to sample the snippets into the makeSchema result.
                    .compose(result->Future.succeededFuture(result.put("dynamicXpath", snippets.get(0).getDynamicXpath())))
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
            ;
        });

    }

    public Future<JsonObject> pickValue(List<JsonObject> options, String taskDescription, String naturalLanguageGuidance){
        return vertx.executeBlocking(blocking->{
            this.strategy.pickValue(options, taskDescription, naturalLanguageGuidance)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
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

    public Future<JsonObject> pickResourceParameterValue(List<JsonObject>options, String query, String taskDescription){
        return vertx.executeBlocking(blocking->{
            this.strategy.pickResourceParameterValue(options, query, taskDescription)
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });
    }


}
