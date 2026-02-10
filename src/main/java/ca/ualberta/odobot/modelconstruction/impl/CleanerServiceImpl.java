package ca.ualberta.odobot.modelconstruction.impl;

import ca.ualberta.odobot.modelconstruction.CleanerService;
import ca.ualberta.odobot.modelconstruction.CleaningStrategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;


public class CleanerServiceImpl implements CleanerService {

    private JsonObject config;
    private Vertx vertx;
    private CleaningStrategy cleaningStrategy;

    public CleanerServiceImpl(Vertx vertx, JsonObject config, CleaningStrategy strategy) {
        this.config = config;
        this.vertx = vertx;
        this.cleaningStrategy = strategy;
    }





    @Override
    public Future<String> cleanHTML(String input) {

        return cleaningStrategy.cleanHTML(input);

    }

    public Future<JsonObject> toNodeLinks(String input){
        return cleaningStrategy.toNodeLinks(input);
    }


}
