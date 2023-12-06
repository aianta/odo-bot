package ca.ualberta.odobot.semanticflow.model.semantictrace.strategy;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.semantictrace.SemanticTrace;
import io.vertx.core.Future;

public interface SemanticTraceConstructionStrategy {

    String name();

    Future<SemanticTrace> construct(Timeline timeline);


}
