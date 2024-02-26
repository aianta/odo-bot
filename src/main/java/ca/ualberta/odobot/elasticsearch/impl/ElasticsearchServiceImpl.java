package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;

import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.mapping.DateProperty;
import co.elastic.clients.elasticsearch.core.*;

import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;

import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.BinaryData;
import co.elastic.clients.util.ContentType;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

import static ca.ualberta.odobot.logpreprocessor.Constants.EXECUTIONS_INDEX;

public class ElasticsearchServiceImpl implements ElasticsearchService {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchServiceImpl.class);


    //Constants
    private static final Time keepAliveValue = Time.of(t->t.time("1m"));

    //Elasticsearch communication
    private RestClient restClient;
    private ElasticsearchTransport transport;
    private ElasticsearchClient client;

    public ElasticsearchServiceImpl(Vertx vertx, String host, int port){
        log.info("Initializing Elasticsearch client");
        restClient = RestClient.builder(new HttpHost(host, port)).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    public Future<Set<String>> getAliases(String pattern){
        try{
            Map<String, IndexAliases> results =  client.indices().getAlias(aliasRequest->aliasRequest.index(pattern)).result();
            return Future.succeededFuture(results.keySet());
        }catch (IOException error){
            log.error(error.getMessage(), error);
            return Future.failedFuture(error);
        }
    }


    /**
     * See {@link #fetchAndSortAll(String, JsonArray)}
     */
    public Future<List<JsonObject>> fetchAll(String index){
        return fetchAndSortAll(index, null);
    }

    /**
     * Fetches all documents stored in the specified index.
     *
     * Note: This method accumulates the results in an ArrayList, and thus could scale poorly with
     * large datasets.
     *
     * TODO -> Consider a scalable implementation...
     *
     * @param index index from which to retrieve documents.
     * @param sortOptions a json array of elasticsearch sort options (see: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html)
     * @return A list of all available documents from that index.
     */
    @Override
    public Future<List<JsonObject>> fetchAndSortAll(String index, JsonArray sortOptions) {
        //Do this in a separate thread so vertx event loop doesn't get blocked.
        Promise<List<JsonObject>> promise = Promise.promise();


        promise.future()
                .onComplete(data->log.info("got data back from thread!"));

        FetchAllTask task = new FetchAllTask(promise, client, index, sortOptions);
        Thread thread = new Thread(task);
        thread.start();



        return promise.future();
    }

    public Future<JsonObject> update(JsonObject document, String id, String index ){
        Promise<JsonObject> promise = Promise.promise();
        try{
            BinaryData binaryData = BinaryData.of(document.toBuffer().getBytes(), ContentType.APPLICATION_JSON);

            IndexResponse response = client.index(i->i
                    .index(index)
                    .id(id)
                    .document(binaryData)
            );
            log.info("updated execution info");
            log.info("{}", response.result().jsonValue());
        } catch (IOException ioException) {
            log.error(ioException.getMessage(), ioException);
            promise.fail(ioException);
        }

        return promise.future();
    }

    @Override
    public Future<Void> saveIntoIndex(List<JsonObject> items, String index) {
        try{

            boolean indexExists = client.indices().exists(e->e.index(index)).value();

            if(!indexExists){
                //If the index doesn't exist, create one
                client.indices().create(create->create
                        .index(index)
                        .mappings(mapping->mapping
                                        .properties("timestamp", value->
                                                value.date(d->d.format("basic_date_time"))
                                ))
                );
            }

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
                log.error("Attempted to insert the following items: ");
                items.forEach(item->log.error("{}", item.encodePrettily()));
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

    @Override
    public Future<Void> deleteIndex(String index) {
        try{
            DeleteIndexRequest.Builder request = new DeleteIndexRequest.Builder().index(index);
            client.indices().delete(request.build());

        } catch (IOException ioException) {
            log.error("Error deleting es index: {}", index);
            log.error(ioException.getMessage(), ioException);
            return Future.failedFuture(ioException);
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<BasicExecution> updateExecution(BasicExecution execution) {
        return update(execution.toJson(), execution.id().toString(), EXECUTIONS_INDEX).compose(
                data->Future.succeededFuture(execution)
        );
    }

}
