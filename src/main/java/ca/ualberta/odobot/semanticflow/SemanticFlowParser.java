package ca.ualberta.odobot.semanticflow;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);

    @Override
    public Completable rxStart() {

        EventLogs eventLogs = EventLogs.getInstance();
//        eventLogs.testSearch().forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
        List<JsonObject> events = eventLogs.fetchAll("fused-index");
        events.forEach(jsonObject -> log.info(jsonObject.encodePrettily()));
        log.info("{} events", events.size());

        return super.rxStart();
    }
}
