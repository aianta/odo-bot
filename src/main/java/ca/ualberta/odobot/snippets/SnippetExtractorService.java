package ca.ualberta.odobot.snippets;

import ca.ualberta.odobot.snippets.impl.SnippetExtractorServiceImpl;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

@ProxyGen
public interface SnippetExtractorService {

    static SnippetExtractorService create(Vertx vertx, SqliteService sqliteService){return new SnippetExtractorServiceImpl(vertx, sqliteService); }

    static SnippetExtractorService createProxy(Vertx vertx, String address){
        return new SnippetExtractorServiceVertxEBProxy(vertx, address);
    }

    Future<Void> setDynamicXPaths(List<JsonObject> xpaths);

    Future<Void> saveSnippets(JsonObject timelineEntityData);

}
