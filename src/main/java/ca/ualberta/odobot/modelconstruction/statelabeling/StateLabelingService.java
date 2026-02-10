package ca.ualberta.odobot.modelconstruction.statelabeling;

import ca.ualberta.odobot.modelconstruction.statelabeling.StateLabelingServiceVertxEBProxy;
import ca.ualberta.odobot.modelconstruction.statelabeling.impl.StateLabelingServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface StateLabelingService {

    static StateLabelingService create(Vertx vertx, JsonObject config){
        return new StateLabelingServiceImpl(vertx, config);
    }

    static StateLabelingService createProxy(Vertx vertx, String address){
        return new StateLabelingServiceVertxEBProxy(vertx, address);
    }

    Future<JsonObject> generateStateLabeling(String clusterId, List<JsonObject> snapshots);

}
