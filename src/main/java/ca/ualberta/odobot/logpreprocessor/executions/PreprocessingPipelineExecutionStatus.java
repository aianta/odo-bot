package ca.ualberta.odobot.logpreprocessor.executions;

import io.vertx.core.json.JsonObject;

public interface PreprocessingPipelineExecutionStatus {

    String name();

    JsonObject data();

    JsonObject toJson();
}
