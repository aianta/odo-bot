package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface AIStrategy {

    /**
     * Given a sample of HTML snippets, produce an XML schema that can be used to validate
     * XML Objects produced from the snippets.
     *
     * @param snippets a sample of HTML snippets
     * @return A future which, if successfully contains a JsonObject of the following form:
     *
     * <pre>
     *     {
     *         "schema": [xsd schema],
     *         [snippetID 1]: [xml corresponding to snippet 1],
     *         [snippetID_2]: [xml corresponding to snippet 2],
     *         ...
     *     }
     *
     * </pre>
     *
     */
    Future<JsonObject> makeSchema(List<Snippet> snippets);

    Future<SemanticObject> makeObject(Snippet snippet, SemanticSchema schema);

    Future<SemanticObject> makeObject(String html, SemanticSchema schema);

    Future<SemanticObject> pickParameterValue(List<SemanticObject> options, String query);

}
