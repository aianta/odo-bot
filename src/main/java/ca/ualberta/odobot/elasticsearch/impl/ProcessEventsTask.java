package ca.ualberta.odobot.elasticsearch.impl;

import ca.ualberta.odobot.semanticflow.JsonDataUtility;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.Time;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A generic task which fetches events from ElasticSearch and facilitates stream processing of them such that they are not all brought into memory at once.
 */
public class ProcessEventsTask extends AbstractElasticsearchTask implements Runnable{

    private static final Logger log = LoggerFactory.getLogger(ProcessEventsTask.class.getName());
    protected static final Time keepAliveValue = Time.of(t->t.time("10m"));

    protected ElasticsearchClient client;
    protected Promise<Void> promise;
    protected String index;
    protected JsonArray sortOptions;
    protected SortOptions esSortOptions;
    protected String flightIdentifier;
    protected String flightIdentifierField;
    protected int limit;

    private int streamedDocumentCount = 0;

    /**
     * A filter function used to decide if an event is emitted by this task.
     */
    Predicate<JsonObject> eventFilter;

    /**
     * The function which is called for every event matching the predicate {@link ProcessEventsTask#eventFilter}.
     */
    Consumer<JsonObject> eventConsumer;

    public static class Builder{
        private ElasticsearchClient client;
        private Promise<Void> promise;
        private String index;
        private JsonArray sortOptions;
        protected String flightIdentifier;
        protected String flightIdentifierField;
        protected Predicate<JsonObject> eventFilter;
        protected Consumer<JsonObject> eventConsumer;
        private int limit = -1;

        public Builder client(ElasticsearchClient client){
            this.client = client;
            return this;
        }

        public Builder promise(Promise<Void> promise){
            this.promise = promise;
            return this;
        }

        public Builder limit(int limit){
            this.limit = limit;
            return this;
        }

        public Builder index(String index){
            this.index = index;
            return this;
        }

        public Builder sortOptions(JsonArray sortOptions){
            this.sortOptions = sortOptions;
            return this;
        }

        public Builder flightIdentifier(String flightIdentifier){
            this.flightIdentifier = flightIdentifier;
            return this;
        }

        public Builder flightIdentifierField(String flightIdentifierField){
            this.flightIdentifierField = flightIdentifierField;
            return this;
        }

        public Builder eventFilter(Predicate<JsonObject> eventFilter){
            this.eventFilter = eventFilter;
            return this;
        }

        public Builder eventConsumer(Consumer<JsonObject> eventConsumer){
            this.eventConsumer = eventConsumer;
            return this;
        }

        public ProcessEventsTask build(){
            if (this.client == null){
                String error = "Elasticsearch client is null";
                log.error(error);
                throw new RuntimeException(error);
            }

            if (this.index == null){
                String error = "No Elasticsearch index provided!";
                log.error(error);
                throw new RuntimeException(error);
            }
            if (this.sortOptions == null){
                this.sortOptions = new JsonArray();
            }

            if(this.eventFilter == null){
                this.eventFilter = event -> true;
                log.warn("No event filter was specified, all events will be emitted.");
            }

            if (this.eventConsumer == null){
                String error = "No event consumer was specified.";
                log.error(error);
                throw new RuntimeException(error);
            }

            if (this.promise == null){
                this.promise = Promise.promise();
            }

            if (this.flightIdentifier != null && limit != -1){
                log.warn("Limit option is not supported when flight identifier is specified!");
            }

            if (this.flightIdentifierField != null && !this.flightIdentifierField.endsWith("keyword")){
                log.warn("Flight identifier field {} does not end with '.keyword'...", this.flightIdentifierField);
            }

            if(this.flightIdentifier != null && this.flightIdentifierField == null){
                String error = "A flightIdentifier was specified %s, therefore the flightIdentifierField must also be defined but it is null!".formatted(this.flightIdentifier);
                log.error(error);
                throw new RuntimeException(error);
            }
            return new ProcessEventsTask(this);
        }
    }

    public ProcessEventsTask(Builder builder){
        this.client = builder.client;
        this.promise = builder.promise;
        this.index = builder.index;
        this.sortOptions = builder.sortOptions;
        this.flightIdentifier = builder.flightIdentifier;
        this.flightIdentifierField = builder.flightIdentifierField;
        this.eventFilter = builder.eventFilter;
        this.eventConsumer = builder.eventConsumer;
        this.limit = builder.limit;
        esSortOptions = processSortOptions(this.sortOptions);
    }

    public Future<Void> getFuture(){
        return this.promise.future();
    }


    @Override
    public void run() {
        streamedDocumentCount = 0;
        if(flightIdentifier == null){
            //If no flight identifier is specified, retrieve all documents in the index.
            log.info("Fetching all documents in es index:{}", index);
            fetch((String pitId, List<FieldValue> sortInfo)->fetchAllRequest(pitId, keepAliveValue, esSortOptions, sortInfo));
        }else{
            log.info("Fetching all events for flight: {} in es index: {}", flightIdentifier, index);
            fetch((String pitId, List<FieldValue> sortInfo)->fetchAllRequestV2(pitId, keepAliveValue, esSortOptions, sortInfo, flightIdentifier, flightIdentifierField));
        }
    }

    /**
     * Fetches documents
     *
     * @param requestFunction a function that returns the desired search request to execute given a String:PIT Id and List<FieldValue>:sortInfo
     * @return A list of all available documents from that index.
     */
    protected void fetch(BiFunction<String, List<FieldValue>, SearchRequest> requestFunction){

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
                promise.complete();
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

                        if(eventFilter.test(result)){
                            streamedDocumentCount++;
                            eventConsumer.accept(result);
                            if (limit != -1 && streamedDocumentCount > limit){
                                break;
                            }
                        }
                        sortInfo = curr.sort();
                    }

                    if (limit != -1 && streamedDocumentCount > limit){
                        break;
                    }

                    //SearchRequest nextRequest = fetchAllRequest(search.pitId(), keepAliveValue, options, sortInfo);
                    SearchRequest nextRequest = requestFunction.apply(search.pitId(), sortInfo);
                    search = client.search(nextRequest, JsonData.class);
                }


                log.info("Done! streamed {} documents.", streamedDocumentCount);


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
        promise.tryComplete();
    }


}
