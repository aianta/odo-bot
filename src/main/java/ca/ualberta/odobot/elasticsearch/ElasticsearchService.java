package ca.ualberta.odobot.elasticsearch;

import ca.ualberta.odobot.elasticsearch.impl.ElasticsearchServiceImpl;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import ca.ualberta.odobot.snippets.SnippetExtractorService;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for interacting with Elasticsearch throughout odobot.
 */
@ProxyGen
public interface ElasticsearchService {

    static ElasticsearchService create(Vertx vertx, String host, int port, SnippetExtractorService snippetExtractorService){
        return new ElasticsearchServiceImpl(vertx, host, port, snippetExtractorService);
    }

    static ElasticsearchService createProxy(Vertx vertx, String address){
        return new ElasticsearchServiceVertxEBProxy(vertx, address);
    }

    /**
     * Return all indices matching the given pattern.
     * @param pattern
     * @return
     */
    Future<Set<String>> getAliases(String pattern);

    /**
     * Returns the names of the flights contained within the specified index as a set of strings.
     * @param index the index to search.
     * @param identifierField the field whose value uniquely identifies flights in the specified index, for example 'flight_name' or 'flightID'.
     * @return Future containing a set of flight strings ex: ['selenium-test-ep-1']
     */
    Future<Set<String>> getFlights(String index, String identifierField);

    /**
     * Returns all documents in the specified elasticsearch index.
     *
     * This can require significant amounts of RAM.
     * @param index
     * @return
     */
    Future<List<JsonObject>> fetchAll(String index);


    /**
     * Old method originally used to retrieve events for flights/traces when each flight/trace had a dedicated index in elasticsearch. This design lead to
     * oversharding and was not scalable for larger datasets.
     */
    Future<List<JsonObject>> fetchAndSortAll(String index, JsonArray sortOptions);

    /**
     * Deprecated: Vertx does not like returning maps over service proxies. Would have to debug this before it can be used again properly.
     *
     * Like {@link #fetchFlightEvents(String, String, String, JsonArray)} only accepts a list of flight identifiers. So it can retrieve multiple timelines sequentially
     * to avoid out of memory exceptions.
     *
     * @param index the index containing the events of the specified flights
     * @param flightIdentifiers A list of timeline/flightIds to retrieve
     * @param flightIdentifierField the field containing the flight identifier. Should probably end in '.keyword'
     * @param sortOptions Options regarding the order of the retrieved documents. Right now, this is treated as a boolean, any non-null value will return documents oldest to newest, while any null value will return documents using the default elasticsearch _score value.
     * @return a map of flightIds and associated events as a list of json objects.
     */
    @Deprecated
    Future<Map<String,List<JsonObject>>> fetchMultipleFlightEvents(String index, List<String> flightIdentifiers, String flightIdentifierField, JsonArray sortOptions);


    /**
     * Returns all documents associated with a particular flight from a specified index. These documents will correspond to events scraped from LogUI's mongoDB.
     * @param index the index containing the events of the specified flight
     * @param flightIdentifier the name of the flight whose events to retrieve.
     * @param flightIdentifierField the field containing the flight identifier. Should probably end in '.keyword'
     * @param sortOptions Options regarding the order of the retrieved documents. Right now, this is treated as a boolean, any non-null value will return documents oldest to newest, while any null value will return documents using the default elasticsearch _score value.
     * @return The corresponding documents matching the specified criteria.
     */
    Future<List<JsonObject>> fetchFlightEvents(String index, String flightIdentifier, String flightIdentifierField, JsonArray sortOptions);

    /**
     *
     * @param index the index containing the events of the specified flight
     * @param flightIdentifier the name of the flight whose events will be used to find snippets.
     * @param flightIdentifierField the field containing the flight identifier. Should probably end in '.keyword'
     * @param sortOptions Options regarding the order of the retrieved documents. Right now, this is treated as a boolean, any non-null value will return documents oldest to newest, while any null value will return documents using the default elasticsearch _score value.
     * @return
     */
    Future<List<JsonObject>> findSnippetsInFlight(String index, String flightIdentifier, String flightIdentifierField, JsonArray sortOptions);

    /**
     * Inserts the specified items into the specified elasticsearch index.
     * @param items items to be inserted into the index
     * @param index index into which the items are inserted.
     * @return
     */
    Future<Void> saveIntoIndex(List<JsonObject> items, String index);

    Future<Void> deleteIndex(String index);

    Future<BasicExecution> updateExecution(BasicExecution execution);
}
