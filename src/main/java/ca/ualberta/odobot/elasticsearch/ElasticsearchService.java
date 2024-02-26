package ca.ualberta.odobot.elasticsearch;

import ca.ualberta.odobot.elasticsearch.impl.ElasticsearchServiceImpl;
import ca.ualberta.odobot.logpreprocessor.executions.impl.BasicExecution;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import java.util.List;
import java.util.Set;

/**
 * Interface for interacting with Elasticsearch throughout odobot.
 */
@ProxyGen
public interface ElasticsearchService {

    static ElasticsearchService create(Vertx vertx, String host, int port){
        return new ElasticsearchServiceImpl(vertx, host, port);
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

    Future<List<JsonObject>> fetchAll(String index);

    Future<List<JsonObject>> fetchAndSortAll(String index, JsonArray sortOptions);

    Future<Void> saveIntoIndex(List<JsonObject> items, String index);

    Future<Void> deleteIndex(String index);

    Future<BasicExecution> updateExecution(BasicExecution execution);
}
