package ca.ualberta.odobot.tpg.analysis.metrics;

import io.vertx.core.json.JsonObject;

public interface MetricComponent {

    JsonObject toJson();

}
