package ca.ualberta.odobot.timeline;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.timeline.impl.TimelineServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface TimelineService {

    static TimelineService create(Vertx vertx){
        return new TimelineServiceImpl(vertx);
    }

    static TimelineService createProxy(Vertx vertx, String address){
        return new TimelineServiceVertxEBProxy(vertx, address);
    }

    Future<Timeline> parse(List<JsonObject> rawEvents);

}
