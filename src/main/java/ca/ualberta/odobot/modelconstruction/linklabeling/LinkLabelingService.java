package ca.ualberta.odobot.modelconstruction.linklabeling;

import ca.ualberta.odobot.modelconstruction.linklabeling.impl.LinkLabelingServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ProxyGen
public interface LinkLabelingService {

    static LinkLabelingService create(JsonObject config) {
        return new LinkLabelingServiceImpl(config);
    }

    static LinkLabelingService createProxy(Vertx vertx, String address) {
        return new LinkLabelingServiceVertxEBProxy(vertx, address);
    }

    Future<JsonObject> labelLink(String rawHref, String normalizedHref);

}
