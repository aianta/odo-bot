package ca.ualberta.odobot.elasticsearch;

import ca.ualberta.odobot.elasticsearch.impl.ElasticsearchServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;


import java.util.List;

/**
 * Interface for interacting with Elasticsearch throughout odobot.
 */
@ProxyGen
public interface ElasticsearchService {

    static ElasticsearchService create(Vertx vertx){
        return new ElasticsearchServiceImpl(vertx);
    }

    static ElasticsearchService createProxy(Vertx vertx, String address){
        return new ElasticsearchServiceVertxEBProxy(vertx, address);
    }

    Future<List<JsonObject>> fetchAll(String index);

}
