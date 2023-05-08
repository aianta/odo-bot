package ca.ualberta.odobot.timeline.impl;

import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.timeline.TimelineService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;


public class TimelineServiceImpl implements TimelineService {

    public TimelineServiceImpl(Vertx vertx){

    }

    @Override
    public Future<Timeline> parse(List<JsonObject> rawEvents) {
        SemanticSequencer sequencer = new SemanticSequencer();
        Timeline result = sequencer.parse(rawEvents);
        return Future.succeededFuture(result);
    }
}
