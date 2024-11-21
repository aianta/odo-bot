package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {

    Future<JsonObject> makeSchema(List<Snippet> snippets);

}
