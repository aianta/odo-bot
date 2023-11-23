package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;

import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch.core.*;

import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
        List<JsonObject> results = new ArrayList<>();

        //Construct SortOptions from JsonArray
        SortOptions options;
        if(sortOptions == null || sortOptions.size() == 0){
            options = defaultSort();
        }else{
            options = makeSortOptions(sortOptions);
        }

        /**
         * Procedure:
         *      Create point-in-time (PIT) to freeze the index and get consistent results.
         *      Fetch first page of results.
         *      Keep fetching results using 'search_after' until we run out.
         *      Delete the PIT.
         *
         *     https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html#search-after
         */
        try{


            boolean indexExists = client.indices().exists(e->e.index(index)).value();
            if(!indexExists){
                //If the index doesn't exist there is nothing to fetch.
                return Future.succeededFuture(List.of());
            }

            log.info("Creating PIT");

            //Index is specified through PIT request.
            OpenPointInTimeResponse pitResponse = client.openPointInTime(pitRequest->pitRequest.index(index).keepAlive(keepAliveValue));
            log.info("PIT: {}", pitResponse.id());

            try{
                /**
                 * No sort info for the first request, that's what makes it the initial request.
                 * NOTE: DO NOT CONFUSE SORT INFO FOR SORT OPTIONS!!
                 */
                log.info("Harvesting documents from index: {}", index);
                SearchRequest initialRequest = fetchAllRequest(pitResponse.id(), keepAliveValue, options, null);

                SearchResponse<JsonData> search = client.search(initialRequest, JsonData.class);

                //Recurse!
                fetchAll(search, initialRequest, options, results);
                log.info("Done! got {} documents.", results.size());


            }catch (ElasticsearchException ese){
                log.error("Error in search request during fetch!");
                log.error(ese.getMessage(), ese);
                return Future.failedFuture(ese);
            }catch (IOException ioe){
                log.error(ioe.getMessage(), ioe);
                return Future.failedFuture(ioe);
            }finally {
                //Finally, delete the PIT once we're done.
                log.info("Deleting PIT: {}", pitResponse.id());
                ClosePointInTimeResponse closePITResponse = client.closePointInTime(close->close.id(pitResponse.id()));
                log.info("{}",closePITResponse.succeeded());
            }

        }catch (IOException ioe){
            log.error("Error fetching documents from es index: {} with sort options: \n{}", index, sortOptions == null?"null":sortOptions.encodePrettily());
            log.error(ioe.getMessage(), ioe);
            return Future.failedFuture(ioe);
        }

        return Future.succeededFuture(results);
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

    private List<JsonObject> fetchAll(SearchResponse<JsonData> response, SearchRequest request, SortOptions sortOptions, List<JsonObject> resultsSoFar) throws IOException {

        log.info("Response: {}", response.toString());

        //Termination condition: response contains 0 results.
        if(response.hits().hits().size() == 0){
            return resultsSoFar;
        }

        //Otherwise add to our results and formulate the next search request
        Iterator<Hit<JsonData>> it = response.hits().hits().iterator();
        List<FieldValue> sortInfo = new ArrayList<>();
        while (it.hasNext()){
            Hit<JsonData> curr = it.next();
            resultsSoFar.add(JsonDataUtility.fromJsonData(curr.source()));
            sortInfo = curr.sort();
            //log.info("SortInfo: {}", sortInfo.toString());
        }

        //Update the search request with the last sort information from the last result.
        SearchRequest nextRequest = fetchAllRequest(response.pitId(), keepAliveValue, sortOptions,  sortInfo);

        SearchResponse<JsonData> search = client.search(nextRequest, JsonData.class);
        return fetchAll(search, request, sortOptions, resultsSoFar);
    }

    /**
     * Support json sort options as described here:
     * https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html
     *
     * @return
     */
    private SortOptions makeSortOptions(JsonArray sortOptions){

        /**
         * TODO - actually implement this properly.
         *
         * Check issue and question to see if anyone responded to the problem we were having initially implementing this.
         * https://github.com/elastic/elasticsearch-java/issues/573
         * https://stackoverflow.com/questions/76214016/class-cast-exception-when-creating-elasticsearch-sortoptions-using-builder-withj
         */

        return SortOptions.of(b->b.field(f->f.field("timestamps_eventTimestamp").order(SortOrder.Asc))); //Oldest event first
    }

    private SortOptions defaultSort(){
        return SortOptions.of(b->b.field(f->f.field("_score").order(SortOrder.Desc))); //Default to using _score sort.
    }

    private SearchRequest fetchAllRequest(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo){

        //Build request
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .size(100)
                .pit(pit->pit.id(pitId).keepAlive(keepAliveValue))
                .query(q->q.matchAll(v->v.withJson(new StringReader("{}"))))
                .trackTotalHits(TrackHits.of(th->th.enabled(false)));

        //If we were given sort options, add them to the request now
        if(sortOptions != null){
            requestBuilder.sort(sortOptions);
        }


        if (sortInfo != null){
            requestBuilder.searchAfter(sortInfo);
        }

        return requestBuilder.build();
    }
}
