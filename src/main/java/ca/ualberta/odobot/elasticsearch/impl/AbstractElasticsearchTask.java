package ca.ualberta.odobot.elasticsearch.impl;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import io.vertx.core.json.JsonArray;

import java.io.StringReader;
import java.util.List;

public abstract class AbstractElasticsearchTask {

    private static final String ELASTICSEARCH_TIMEOUT = "1800000ms"; //30 min

    protected SortOptions defaultSort(){
        return SortOptions.of(b->b.field(f->f.field("_score").order(SortOrder.Desc))); //Default to using _score sort.
    }

    protected SortOptions processSortOptions(JsonArray sortOptions){
        if(sortOptions == null || sortOptions.size() == 0){
            return defaultSort();
        }else{
            return makeSortOptions(sortOptions);
        }
    }

    /**
     * Support json sort options as described here:
     * https://www.elastic.co/guide/en/elasticsearch/reference/current/sort-search-results.html
     *
     * @return
     */
    protected SortOptions makeSortOptions(JsonArray sortOptions){

        /**
         * TODO - actually implement this properly.
         *
         * Check issue and question to see if anyone responded to the problem we were having initially implementing this.
         * https://github.com/elastic/elasticsearch-java/issues/573
         * https://stackoverflow.com/questions/76214016/class-cast-exception-when-creating-elasticsearch-sortoptions-using-builder-withj
         */

        return SortOptions.of(b->b.field(f->f.field("timestamps_eventTimestamp").order(SortOrder.Asc))); //Oldest event first
    }

    protected SearchRequest fetchAllRequestV2(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo, String flightIdentifier, String flightIdentifierField){

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

    protected SearchRequest fetchAllRequest(String pitId, Time keepAliveValue, SortOptions sortOptions, List<FieldValue> sortInfo){

        //Build request
        SearchRequest.Builder requestBuilder = commonRequestBuilder(pitId, keepAliveValue)
                .query(q->q.matchAll(v->v.withJson(new StringReader("{}"))));


        return handleSorting(requestBuilder, sortOptions, sortInfo);
    }

    /**
     * Construct a search request builder object with common settings.
     * @return
     */
    protected SearchRequest.Builder commonRequestBuilder(String pitId, Time keepAliveValue){
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .size(100)
                .pit(pit->pit.id(pitId).keepAlive(keepAliveValue))
                .timeout(ELASTICSEARCH_TIMEOUT)
                .trackTotalHits(TrackHits.of(th->th.enabled(false)));

        return requestBuilder;

    }

    protected SearchRequest handleSorting(SearchRequest.Builder requestBuilder, SortOptions sortOptions, List<FieldValue> sortInfo){
        //If we were given sort options, add them to the request now
        if(sortOptions != null){
            requestBuilder.sort(sortOptions);
        }

        if(sortInfo != null){
            requestBuilder.searchAfter(sortInfo);
        }

        return requestBuilder.build();

    }
}
