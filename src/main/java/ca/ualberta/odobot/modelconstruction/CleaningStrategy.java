package ca.ualberta.odobot.modelconstruction;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface CleaningStrategy {

    public Future<String> cleanHTML(String input);

    public Future<JsonObject> toNodeLinks(String input);
}
