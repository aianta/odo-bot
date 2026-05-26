package ca.ualberta.odobot.telemetry.impl;

import ca.ualberta.odobot.telemetry.TelemetryService;
import ca.ualberta.odobot.telemetry.model.ExperimentResults;
import ca.ualberta.odobot.telemetry.model.TaskInstanceResults;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.BinaryData;
import co.elastic.clients.util.ContentType;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TelemetryServiceImpl implements TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);
    private JsonObject config;
    private Vertx vertx;

    private String experimentResultsIndex = "experiment_results";
    private String taskInstanceResultsIndex = "task_instance_results";

    private RestClient restClient;
    private ElasticsearchTransport transport;
    private ElasticsearchClient client;

    public TelemetryServiceImpl(Vertx vertx, JsonObject config) {
        this.config = config;
        this.vertx = vertx;

        if(config.containsKey("experimentResultsIndex")){
            this.experimentResultsIndex = config.getString("experimentResultsIndex");
        }
        if(config.containsKey("taskInstanceResultsIndex")){
            this.taskInstanceResultsIndex = config.getString("taskInstanceResultsIndex");
        }

        this.restClient = RestClient.builder(new HttpHost(config.getString("esHost"), config.getInteger("esPort"))).build();
        this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    @Override
    public Future<Void> reportExperimentResults(ExperimentResults results) {
        saveToIndex(results, experimentResultsIndex);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> reportTaskResults(TaskInstanceResults results) {
        saveToIndex(results, taskInstanceResultsIndex);
        return Future.succeededFuture();
    }

    private void saveToIndex(JsonObject data, String index){

        try{
            boolean indexExists = client.indices().exists(e->e.index(index)).value();
            if(!indexExists){
                //If the index doesn't exist create it.
                client.indices()
                        .create(create->create.index(index)

                                        .mappings(m->m
                                                        .properties("Details.mismatch_report", p->p.object(o->o.enabled(false)))
                                        )
                        );

            }

            BinaryData binaryData = BinaryData.of(data.toBuffer().getBytes(), ContentType.APPLICATION_JSON);

            IndexRequest<BinaryData> request = IndexRequest.of(i->i
                    .index(index)
                    .id(data.getString("Id"))
                    .document(binaryData)
            );

            IndexResponse response = client.index(request);

            log.info("Indexed document with id {} into index {}. Result: {}", response.id(), index, response.result());

        }catch (IOException ex){

            log.error(ex.getMessage(), ex);
        }
    }
}
