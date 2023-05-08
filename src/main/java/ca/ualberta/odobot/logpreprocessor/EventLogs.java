package ca.ualberta.odobot.logpreprocessor;

import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import io.vertx.core.json.JsonObject;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages the retrieval of application event logs from elasticsearch.
 */
public class EventLogs {
    private static final Logger log = LoggerFactory.getLogger(EventLogs.class);
    private static EventLogs instance = null;

    /**
     * Constants
     */
    private static final String TIMESTAMP_FIELD = "timestamps_eventTimestamp";
    private static final Time keepAliveValue = Time.of(t->t.time("1m"));

    private RestClient restClient;
    private ElasticsearchTransport transport;
    private ElasticsearchClient client;

    public static EventLogs getInstance(){
        if(instance == null){
            instance = new EventLogs();
        }
        return instance;
    }

    private EventLogs(){
        log.info("Initializing Elasticsearch client");
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /**
     * Fetches all events stored in the specified index.
     *
     * Note: This method accumulates the results in an ArrayList, and thus could scale poorly with
     * large datasets.
     *
     * TODO -> Consider a scalable implementation...
     *
     * @param index index from which to retrieve events.
     * @return A list of all available events from that index.
     */
    public List<JsonObject> fetchAll(String index){
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
        //TODO -> Look into exactly how the time parameter works, what other options do we have beyond '1m'
        try{
            log.info("Creating PIT");


            //Index is specified through PIT request.
            OpenPointInTimeResponse pitResponse = client.openPointInTime(pitRequest->pitRequest.index(index).keepAlive(keepAliveValue));
            log.info("PIT: {}", pitResponse.id());

            try{


                /**
                 * No sort info for the first request, that's what makes it the initial request.
                 */
                log.info("Harvesting events from index: {}", index);
                SearchRequest initialRequest = fetchAllRequest(pitResponse.id(), keepAliveValue, null);

                SearchResponse<JsonData> search = client.search(initialRequest,JsonData.class);

                //Recurse!
                fetchAll(search, initialRequest, results);
                log.info("Done! Got {} events.", results.size());

                //Finally, delete the PIT once we're done.
                log.info("Deleting PIT: {}", pitResponse.id());
                ClosePointInTimeResponse closePITResponse = client.closePointInTime(close->close.id(pitResponse.id()));
                log.info("{}",closePITResponse.succeeded());

            }catch (ElasticsearchException esException){
                log.error(esException.getMessage(), esException);
            }
            catch (IOException e){
                log.error(e.getMessage(), e);
            }finally {
                //Finally, delete the PIT once we're done.
                log.info("Deleting PIT: {}", pitResponse.id());
                ClosePointInTimeResponse closePITResponse = client.closePointInTime(close->close.id(pitResponse.id()));
                log.info("{}",closePITResponse.succeeded());
            }

        }catch (IOException e){
            log.error(e.getMessage(),e);
        }
        return results;
    }

    private List<JsonObject> fetchAll(SearchResponse<JsonData> response, SearchRequest request, List<JsonObject> resultsSoFar) throws IOException {

        //Termination condition: response contains 0 results.
        if(response.hits().hits().size() == 0){
            return resultsSoFar;
        }

        //Otherwise add to our results and formulate the next search request
        Iterator<Hit<JsonData>> it = response.hits().hits().iterator();
        List<String> sortInfo = new ArrayList<>();
        while (it.hasNext()){
            Hit<JsonData> curr = it.next();
            resultsSoFar.add(JsonDataUtility.fromJsonData(curr.source()));
            sortInfo = curr.sort();
        }

        //Update the search request with the last sort information from the last result.
        SearchRequest nextRequest = fetchAllRequest(response.pitId(), keepAliveValue, sortInfo);

        SearchResponse<JsonData> search = client.search(nextRequest, JsonData.class);
        return fetchAll(search, request,resultsSoFar);
    }

    private SearchRequest fetchAllRequest(String pitId, Time keepAliveValue, List<String> sortInfo){
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .size(100)

                .pit(pit->pit.id(pitId).keepAlive(keepAliveValue))
                .query(q->q.matchAll(v->v.withJson(new StringReader("{}"))))
                .sort(sort->sort.field(f->f.field(TIMESTAMP_FIELD).order(SortOrder.Asc))) //Oldest event first
                .trackTotalHits(TrackHits.of(th->th.enabled(false)));

        if (sortInfo != null){
            requestBuilder.searchAfter(sortInfo);
        }

        return requestBuilder.build();
    }

    public Iterable<JsonObject> testSearch(){
        List<JsonObject> results = new ArrayList<>();

        try{
            log.info("Fetching documents from fused-index");
            SearchResponse<JsonData> search = client.search(s-> s
                            .index("fused-index")
                            .query(q->q
                                    .matchAll(v->v.withJson(new StringReader("{}"))))
                    , JsonData.class
            );

            for (Hit<JsonData> hit: search.hits().hits()){
                JsonObject json = JsonDataUtility.fromJsonData(hit.source());
                results.add(json);

            }
        }catch (IOException e){
            log.error(e.getMessage(), e);
        }
        return results;
    }


}
