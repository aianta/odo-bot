package ca.ualberta.odobot.domsequencing;

import ca.ualberta.odobot.domsequencing.impl.DOMSequencingServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;

@ProxyGen
public interface DOMSequencingService {

    static DOMSequencingService create(){
        return new DOMSequencingServiceImpl();
    }

    static DOMSequencingService createProxy(Vertx vertx, String address){
        return new DOMSequencingServiceVertxEBProxy(vertx, address);
    }

    Future<JsonObject> process(String html);

    Future<JsonArray> htmlToXPathSequence(String html);

    Future<JsonArray> htmlToSequence(String html);

    Future<List<JsonObject>> getSequences();

    Future<Void> clearSequences();

    Future<Void> testPatternExtraction(List<JsonObject> data);

    Future<String> getGlobalManifest();

    Future<String> cssQuery(Set<String> query);

    Future<String> getDirectlyFollowsManifest();

    Future<String> getEncodedSequences();

    Future<String> decodeSequences(String encodedSequences);

    Future<String> getEntitiesAndActions();

    Future<List<String>> getTexts(String html);

    Future<JsonObject> getHashedSequences();

    Future<JsonArray> hashAndFlattenDOM(String html);

}
