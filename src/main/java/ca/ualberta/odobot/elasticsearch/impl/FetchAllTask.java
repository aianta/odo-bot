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

public class FetchAllTask implements Runnable{
    private static final Logger log = LoggerFactory.getLogger(FetchAllTask.class);
    private static final Time keepAliveValue = Time.of(t->t.time("10m"));

    private ElasticsearchClient client;
    private Promise<List<JsonObject>> promise;
    private String index;
    private JsonArray sortOptions;

    public  FetchAllTask(Promise<List<JsonObject>> promise, ElasticsearchClient client, String index, JsonArray sortOptions){
        this.promise = promise;
        this.index = index;
        this.sortOptions = sortOptions;
        this.client = client;
    }

    @Override
    public void run() {
        fetchAndSortAll(index, sortOptions);
    }

    /**
     * Fetches all documents stored in the specified index.
     *
     * Note: This method accumulates the results in an ArrayList, and thus could scale poorly with
     * large datasets.
     *
     * TODO -> Consider a scalable implementation...
     * @param index index from which to retrieve documents.
     * @param sortOptions a json array of elasticsearch sort options (see: https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html)
     * @return A list of all available documents from that index.
     */
    private void fetchAndSortAll(String index, JsonArray sortOptions){
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
                SearchRequest initialRequest = fetchAllRequest(pitResponse.id(), keepAliveValue, options, null);

                SearchResponse<JsonData> search = client.search(initialRequest, JsonData.class);

                //Termination condition: response contains 0 results.
                while(search.hits().hits().size() != 0){
                    log.info("Response: {}", search.toString());

                    //add to our results and formulate the next search request
                    Iterator<Hit<JsonData>> it = search.hits().hits().iterator();
                    List<FieldValue> sortInfo = new ArrayList<>();
                    while (it.hasNext()){
                        Hit<JsonData> curr = it.next();
                        results.add(JsonDataUtility.fromJsonData(curr.source()).put("index", curr.index())); //Add the index of the document to our result
                        sortInfo = curr.sort();
                    }

                    SearchRequest nextRequest = fetchAllRequest(search.pitId(), keepAliveValue, options, sortInfo);
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

    private SearchRequest fetchAllRequest(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo){

        //Build request
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .size(100)
                .pit(pit->pit.id(pitId).keepAlive(keepAliveValue))
                .query(q->q.matchAll(v->v.withJson(new StringReader("{}"))))
                .timeout("1800000ms")
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