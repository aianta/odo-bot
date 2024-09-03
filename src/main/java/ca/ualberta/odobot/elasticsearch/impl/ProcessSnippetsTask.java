package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import ca.ualberta.odobot.snippets.SnippetExtractorService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;

public class ProcessSnippetsTask extends FetchAllTask{

    private static final Logger log = LoggerFactory.getLogger(ProcessSnippetsTask.class);

    SnippetExtractorService snippetExtractorService = null;

    public ProcessSnippetsTask(Promise<List<JsonObject>> promise, ElasticsearchClient client, String index, String flightIdentifier, String flightIdentifierField, JsonArray sortOptions, SnippetExtractorService snippetExtractorService) {
        super(promise, client, index, flightIdentifier, flightIdentifierField, sortOptions);
        this.snippetExtractorService = snippetExtractorService;
    }

    public ProcessSnippetsTask(Promise<List<JsonObject>> promise, ElasticsearchClient client, String index, JsonArray sortOptions, SnippetExtractorService snippetExtractorService) {
        super(promise, client, index, sortOptions);
        this.snippetExtractorService = snippetExtractorService;
    }

    @Override
    protected void fetch(BiFunction<String, List<FieldValue>, SearchRequest> requestFunction) {

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

                        //results.add(result); //Add the index of the document to our result
                        processDocument(result);

                        sortInfo = curr.sort();
                    }

                    //SearchRequest nextRequest = fetchAllRequest(search.pitId(), keepAliveValue, options, sortInfo);
                    SearchRequest nextRequest = requestFunction.apply(search.pitId(), sortInfo);
                    search = client.search(nextRequest, JsonData.class);
                }

                //Recurse!
                //fetchAll(search, initialRequest, options, results);
                //log.info("Done! got {} documents.", results.size());


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
        promise.tryComplete(List.of());
    }

    private void processDocument(JsonObject document){
        Future processFuture = snippetExtractorService.saveSnippets(document);
        while (!processFuture.isComplete()){
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
