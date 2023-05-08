package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.EventLogs;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class ElasticsearchServiceImpl implements ElasticsearchService {

    private EventLogs  eventLogs = EventLogs.getInstance();

    public ElasticsearchServiceImpl(Vertx vertx){

    }

    @Override
    public Future<List<JsonObject>> fetchAll(String index) {
        //TODO -> need to extract a generic fetch all logic from EventLogs
        return Future.succeededFuture(eventLogs.fetchAll(index));
    }
}
