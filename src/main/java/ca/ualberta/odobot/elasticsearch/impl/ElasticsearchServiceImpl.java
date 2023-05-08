package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.EventLogs;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.BinaryData;
import co.elastic.clients.util.ContentType;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class ElasticsearchServiceImpl implements ElasticsearchService {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchServiceImpl.class);
    private EventLogs  eventLogs = EventLogs.getInstance();



    private RestClient restClient;
    private ElasticsearchTransport transport;
    private ElasticsearchClient client;

    public ElasticsearchServiceImpl(Vertx vertx, String host, int port){
        log.info("Initializing Elasticsearch client");
        restClient = RestClient.builder(new HttpHost(host, port)).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @Override
    public Future<List<JsonObject>> fetchAll(String index) {
        //TODO -> need to extract a generic fetch all logic from EventLogs
        return Future.succeededFuture(eventLogs.fetchAll(index));
    }

    @Override
    public Future<Void> saveIntoIndex(List<JsonObject> items, String index) {
        try{
            BulkRequest.Builder br = new BulkRequest.Builder();

            items.forEach(document->{

                BinaryData binaryData = BinaryData.of(document.toBuffer().getBytes(), ContentType.APPLICATION_JSON);
                //JsonData esDocument = JsonData.fromJson(document.encode());

                br.operations(op->op
                        .index(idx->idx
                                .index(index)
                                .document(binaryData)
                        ));
            });

            BulkResponse result = client.bulk(br.build());

            if(result.errors()){
                log.error("Bulk insert request resulted in errors!");
                for(BulkResponseItem item: result.items()){
                    if(item.error() != null){
                        log.error("{}", item.error().reason());
                    }
                }
                return Future.failedFuture("Bulk insert request failed with errors!");
            }

        } catch (IOException ioException) {
            log.error("Error bulk inserting documents into {}", index);
            log.error(ioException.getMessage(), ioException);
            return Future.failedFuture(ioException);
        }


        return Future.succeededFuture();
    }


}
