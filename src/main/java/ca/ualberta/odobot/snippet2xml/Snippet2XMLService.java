package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.snippet2xml.impl.Snippet2XMLServiceImpl;
import ca.ualberta.odobot.snippets.Snippet;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface Snippet2XMLService {

    static Snippet2XMLService create(Vertx vertx, JsonObject config, Strategy strategy){return  new Snippet2XMLServiceImpl(vertx, config, strategy);}

    static Snippet2XMLService createProxy(Vertx vertx, String address){
        return new Snippet2XMLServiceVertxEBProxy(vertx, address);
    }

    Future<SemanticObject> getObjectFromSnippet(Snippet snippet, SemanticSchema schema);

    /**
     * Provide a set of snippets from which to generate an XML schema
     * @param snippets
     * @return A json object containing the generated schema under the field 'schema', and the XML objects of the snippets used to generate the schema.
     */
    Future<JsonObject> makeSchema(List<Snippet> snippets);


}
