package ca.ualberta.odobot.domsequencing;

import ca.ualberta.odobot.domsequencing.impl.DOMSequencingServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface DOMSequencingService {

    static DOMSequencingService create(){
        return new DOMSequencingServiceImpl();
    }

    static DOMSequencingService createProxy(Vertx vertx, String address){
        return new DOMSequencingServiceVertxEBProxy(vertx, address);
    }

    Future<JsonObject> process(String html);

    Future<List<JsonObject>> getSequences();

    Future<Void> clearSequences();

}
