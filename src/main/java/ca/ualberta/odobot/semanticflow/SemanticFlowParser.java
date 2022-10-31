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
import java.util.Map;

public class SemanticFlowParser extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SemanticFlowParser.class);

    RestClient restClient;
    ElasticsearchTransport transport;
    ElasticsearchClient client;

    @Override
    public Completable rxStart() {


        log.info("Initializing Elasticsearch client");
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);

        log.info("Fetching documents from fused-index");
        try{
            SearchResponse<JsonData> search = client.search(s-> s
                            .index("fused-index")
                            .query(q->q
                                    .matchAll(v->v.withJson(new StringReader("{}"))))
                    , JsonData.class
            );

            for (Hit<JsonData> hit: search.hits().hits()){
                log.info(hit.source().toString());
                JsonObject json = JsonDataUtility.fromJsonData(hit.source());
                log.info(json.encodePrettily());
            }

        }catch (IOException ioe){
            log.error(ioe.getMessage(), ioe);
        }

        return super.rxStart();
    }
}
