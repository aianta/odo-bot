package ca.ualberta.odobot.cleaner;

import ca.ualberta.odobot.cleaner.impl.CleanerServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ProxyGen
public interface CleanerService {


    static CleanerService create(Vertx vertx, JsonObject config, CleaningStrategy cleaningStrategy) {
        return new CleanerServiceImpl(vertx, config, cleaningStrategy);
    }

    static CleanerService createProxy(Vertx vertx, String address){
        return new CleanerServiceVertxEBProxy(vertx, address);
    }

    Future<String> cleanHTML(String input);

    Future<JsonObject> toNodeLinks(String input);
}
