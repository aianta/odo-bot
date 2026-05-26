package ca.ualberta.odobot.dataentry2label.impl;

import ca.ualberta.odobot.dataentry2label.AIStrategy;
import ca.ualberta.odobot.dataentry2label.DataEntry2LabelService;
import ca.ualberta.odobot.dataentry2label.Strategy;
import ca.ualberta.odobot.guidance.RequestManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class DataEntry2LabelServiceImpl implements DataEntry2LabelService {

    private Vertx vertx;
    private AIStrategy strategy;
    public static String model;

    public DataEntry2LabelServiceImpl(Vertx vertx, JsonObject config, Strategy strategy){
        this.vertx = vertx;
        this.strategy = switch (strategy){
            case OPENAI -> {
                OpenAIStrategy _strategy = new OpenAIStrategy(config);
                RequestManager.newTokenUsageRecordListeners.add(_strategy::onNewTokenUsageRecord);
                model = _strategy.getModel();
                yield _strategy;
            }
        };
    }

    public Future<JsonArray> standardizeLabels(List<JsonObject> labels){
        return vertx.executeBlocking(blocking->{
            this.strategy.standardizeLabels(labels)
                    //standardizeLabels returns a list of integers corresponding with the input labels, resolve those integers back into labels so we can understand the output better.
                    .compose(standarizedLabels->{
                        standarizedLabels.stream()
                                .map(JsonObject.class::cast)
                                .forEach(entry->{
                                    JsonArray resolvedLabels = new JsonArray();
                                    entry.getJsonArray("annotations").stream()
                                            .map(Integer.class::cast)
                                            .forEach(index->resolvedLabels.add(labels.get(index-1).getString("label")));
                                    entry.put("annotations", resolvedLabels);
                                });
                        return Future.succeededFuture(standarizedLabels);
                    })
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail);
        });

    }

    @Override
    public Future<JsonObject> generateLabelAndDescription(JsonObject dataEntryInfo) {

        return vertx.executeBlocking(blocking->
            this.strategy.generateLabelAndDescription(dataEntryInfo)
                    .compose(result->{
                        result.put("xpath", dataEntryInfo.getString("xpath"));
                        if(dataEntryInfo.containsKey("radioGroup")){
                            result.put("radioGroup", dataEntryInfo.getString("radioGroup"));
                        }
                        return Future.succeededFuture(result);
                    })
                    .onSuccess(blocking::complete)
                    .onFailure(blocking::fail)
        );

    }
}
