package ca.ualberta.odobot.dataentry2label.impl;

import ca.ualberta.odobot.dataentry2label.AIStrategy;
import ca.ualberta.odobot.dataentry2label.DataEntry2LabelService;
import ca.ualberta.odobot.dataentry2label.Strategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class DataEntry2LabelServiceImpl implements DataEntry2LabelService {

    private Vertx vertx;
    private AIStrategy strategy;

    public DataEntry2LabelServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this.vertx = vertx;
        this.strategy = switch (strategy){
            case OPENAI -> new OpenAIStrategy(config);
        };
    }

    @Override
    public Future<JsonObject> generateLabelAndDescription(JsonObject dataEntryInfo) {

        return vertx.executeBlocking(blocking->
            this.strategy.generateLabelAndDescription(dataEntryInfo)
                    .compose(result->Future.succeededFuture(result.put("xpath", dataEntryInfo.getString("xpath"))))
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
        );

    }
}
