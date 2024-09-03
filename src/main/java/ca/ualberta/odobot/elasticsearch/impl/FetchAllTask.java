package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import co.elastic.clients.json.JsonData;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;

public class FetchAllTask implements Runnable{
    private static final Logger log = LoggerFactory.getLogger(FetchAllTask.class);
    protected static final Time keepAliveValue = Time.of(t->t.time("10m"));

    private static final String ELASTICSEARCH_TIMEOUT = "1800000ms"; //30 min

    protected ElasticsearchClient client;
    protected Promise<List<JsonObject>> promise;
    protected String index;
    protected JsonArray sortOptions;

    protected SortOptions esSortOptions;

    protected String flightIdentifier;

    protected String flightIdentifierField;

    /**
     * Constructor used to create a FetchAllTask object that retrieves all documents (events) for a given flight/trace from its parent index.
     * @param promise the promise to complete once all documents have been retrieved.
     * @param client the elasticsearch client to use.
     * @param index the index containing the specified flight/trace
     * @param flightIdentifier the identifier of the flight/trace to retrieve events for.
     * @param flightIdentifierField the name of the field containing the identifier. These should probably end in '.keyword'.
     * @param sortOptions Options regarding the sort order of the returned documents. Json array of elasticsearch sort options (see: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html)
     */
    public FetchAllTask (Promise<List<JsonObject>> promise, ElasticsearchClient client, String index, String flightIdentifier, String flightIdentifierField, JsonArray sortOptions){
        this.promise = promise;
        this.index = index;
        this.flightIdentifier = flightIdentifier;
        this.flightIdentifierField = flightIdentifierField;
        this.sortOptions = sortOptions;
        this.client = client;

        //Construct SortOptions from JsonArray
        esSortOptions = processSortOptions(sortOptions);
    }

    /**
     * Constructor used to create a FetchAllTask object that retrieves all documents in an index.
     * @param promise the promise to complete once all documents have been retrieved.
     * @param client the elasticsearch client to use.
     * @param index the index whose documents are to be retrieved.
     * @param sortOptions Options regarding the sort order of the returned documents. Json array of elasticsearch sort options (see: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html)
     */
    public  FetchAllTask(Promise<List<JsonObject>> promise, ElasticsearchClient client, String index, JsonArray sortOptions){
        this.promise = promise;
        this.index = index;
        this.sortOptions = sortOptions;
        this.client = client;

        //Construct SortOptions from JsonArray
        esSortOptions = processSortOptions(sortOptions);
    }

    @Override
    public void run() {
        //If no flight name is specified, retrieve all documents in an index.
        if(flightIdentifier == null){
            log.info("Fetching all documents in es index:{}", index);
            fetch((String pitId, List<FieldValue> sortInfo)->fetchAllRequest(pitId, keepAliveValue, esSortOptions, sortInfo));
        }else{
            //Cannot proceed without an identifier field.
            if(flightIdentifierField == null){
                throw new RuntimeException("No flight identifier field specified!");
            }
            //Identifier field should probably end in 'keyword'.
            if(!flightIdentifierField.endsWith("keyword")){
                log.warn("Flight identifier field: {} does not end with '.keyword'...", flightIdentifierField);
            }

            log.info("Fetching all events for flight: {} in es index: {}", flightIdentifier, index);
            fetch((String pitId, List<FieldValue> sortInfo)->fetchAllRequestV2(pitId, keepAliveValue, esSortOptions, sortInfo, flightIdentifier, flightIdentifierField));
        }
    }


    /**
     * Fetches all documents stored in the specified index.
     *
     * Note: This method accumulates the results in an ArrayList, and thus could scale poorly with
     * large datasets as it places them entirely into memory.
     *
     * TODO -> Consider a scalable implementation...
     * @param requestFunction a function that returns the desired search request to execute given a String:PIT Id and List<FieldValue>:sortInfo
     * @return A list of all available documents from that index.
     */
    protected void fetch(BiFunction<String, List<FieldValue>,SearchRequest> requestFunction){
        List<JsonObject> results = new ArrayList<>();



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
                promise.complete(List.of());
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
                //SearchRequest initialRequest = fetchAllRequest(pitResponse.id(), keepAliveValue, options, null);
                SearchRequest initialRequest = requestFunction.apply(pitResponse.id(), null);

                log.info("ES initial query: {}", initialRequest);

                SearchResponse<JsonData> search = client.search(initialRequest, JsonData.class);

                //Termination condition: response contains 0 results.
                while(search.hits().hits().size() != 0){
                    log.info("Response: {}", search.toString());

                    //add to our results and formulate the next search request
                    Iterator<Hit<JsonData>> it = search.hits().hits().iterator();
                    List<FieldValue> sortInfo = new ArrayList<>();
                    while (it.hasNext()){
                        Hit<JsonData> curr = it.next();

                        JsonObject result = JsonDataUtility.fromJsonData(curr.source()).put("esIndex", curr.index());
                        if(flightIdentifier != null){
                            result.put("flightName", flightIdentifier);
                        }

                        results.add(result); //Add the index of the document to our result
                        sortInfo = curr.sort();
                    }

                    //SearchRequest nextRequest = fetchAllRequest(search.pitId(), keepAliveValue, options, sortInfo);
                    SearchRequest nextRequest = requestFunction.apply(search.pitId(), sortInfo);
                    search = client.search(nextRequest, JsonData.class);
                }

                //Recurse!
                //fetchAll(search, initialRequest, options, results);
                log.info("Done! got {} documents.", results.size());


            }catch (ElasticsearchException ese){
                log.error("Error in search request during fetch!");
                log.error(ese.getMessage(), ese);
                promise.fail(ese);
            }catch (IOException ioe){
                log.error(ioe.getMessage(), ioe);
                promise.fail(ioe);
            }finally {
                //Finally, delete the PIT once we're done.
                log.info("Deleting PIT: {}", pitResponse.id());
                ClosePointInTimeResponse closePITResponse = client.closePointInTime(close->close.id(pitResponse.id()));
                log.info("{}",closePITResponse.succeeded());
            }

        }catch (IOException ioe){
            log.error("Error fetching documents from es index: {} with sort options: \n{}", index, sortOptions == null?"null":sortOptions.encodePrettily());
            log.error(ioe.getMessage(), ioe);
            promise.fail(ioe);
        }
        promise.tryComplete(results);
    }

    private SortOptions defaultSort(){
        return SortOptions.of(b->b.field(f->f.field("_score").order(SortOrder.Desc))); //Default to using _score sort.
    }

    private SortOptions processSortOptions(JsonArray sortOptions){
        if(sortOptions == null || sortOptions.size() == 0){
            return defaultSort();
        }else{
            return makeSortOptions(sortOptions);
        }
    }

    private SearchRequest fetchAllRequestV2(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo, String flightIdentifier, String flightIdentifierField){

        /**
         * Using the Filter Context strategy from the link below:
         * https://opster.com/guides/elasticsearch/search-apis/elasticsearch-exact-match/
         *
         * Filters should allow for caching on elasticsearch's end improving performance.
         */

        SearchRequest.Builder requestBuilder = commonRequestBuilder(pitId, keepAliveValue)
                //The field 'flight_name' is defined in the scrape_mongo_v2.sh script used to scrape flight data from LogUI's mongoDB into elasticsearch.
                .query(q->q.bool(b->b.filter(f->f.term(t->t.field(flightIdentifierField).value(flightIdentifier)))));

        return handleSorting(requestBuilder, sortOptions, sortInfo);
    }

    /**
     * Construct a search request builder object with common settings.
     * @return
     */
    private SearchRequest.Builder commonRequestBuilder(String pitId, Time keepAliveValue){
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .size(100)
                .pit(pit->pit.id(pitId).keepAlive(keepAliveValue))
                .timeout(ELASTICSEARCH_TIMEOUT)
                .trackTotalHits(TrackHits.of(th->th.enabled(false)));

        return requestBuilder;

    }

    private SearchRequest handleSorting(SearchRequest.Builder requestBuilder, SortOptions sortOptions, List<FieldValue> sortInfo){
        //If we were given sort options, add them to the request now
        if(sortOptions != null){
            requestBuilder.sort(sortOptions);
        }

        if(sortInfo != null){
            requestBuilder.searchAfter(sortInfo);
        }

        return requestBuilder.build();

    }

    private SearchRequest fetchAllRequest(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo){

        //Build request
        SearchRequest.Builder requestBuilder = commonRequestBuilder(pitId, keepAliveValue)
                .query(q->q.matchAll(v->v.withJson(new StringReader("{}"))));


        return handleSorting(requestBuilder, sortOptions, sortInfo);
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
}