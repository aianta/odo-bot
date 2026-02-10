package ca.ualberta.odobot.modelconstruction.statelabeling;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {


    Future<JsonObject> generateStateLabeling(String clusterId, List<JsonObject> snapshots);

}
