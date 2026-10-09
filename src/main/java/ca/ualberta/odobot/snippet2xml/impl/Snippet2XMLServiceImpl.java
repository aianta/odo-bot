package ca.ualberta.odobot.snippet2xml.impl;

import ca.ualberta.odobot.common.LlmCallScope;
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


public class Snippet2XMLServiceImpl implements Snippet2XMLService {

    private static final Logger log = LoggerFactory.getLogger(Snippet2XMLServiceImpl.class);
    private Vertx vertx;
    private JsonObject config;
    private Strategy strategyType;
    private AIStrategy strategy;
    public static String model;

    public Snippet2XMLServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this(vertx, config, strategy, null);
    }

    /**
     * @param scope the task this service makes LLM calls for, or null if it is not built for a task.
     */
    private Snippet2XMLServiceImpl(Vertx vertx, JsonObject config, Strategy strategy, LlmCallScope scope){
        this.vertx = vertx;
        this.config = config;
        this.strategyType = strategy;
        this.strategy = switch (strategy){
            case OPENAI -> {
                OpenAIStrategy _strategy = new OpenAIStrategy(config, scope);
                model = _strategy.getModel();
                yield _strategy;
            }
        };



    }

    /**
     * @return a copy of this service whose LLM calls use the settings of, and are counted toward, the given task.
     */
    public Snippet2XMLServiceImpl withScope(LlmCallScope scope){
        return new Snippet2XMLServiceImpl(vertx, config, strategyType, scope);
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
