package ca.ualberta.odobot.modelconstruction;

import ca.ualberta.odobot.modelconstruction.impl.CleanerServiceImpl;
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

    /**
     * Given a string containing an HTML document, produces a cleaned HTML, then outputs a node-links JSON representation of the DOM graph
     * @param input HTML document string
     * @return A JSON object in node-links format representing the cleaned DOM graph.
     */
    Future<JsonObject> toNodeLinks(String input);


    Future<JsonObject> toElementAnnotationQuery(String html, String targetElementXpath);
}
