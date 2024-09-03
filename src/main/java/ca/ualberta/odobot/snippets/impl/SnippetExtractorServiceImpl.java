package ca.ualberta.odobot.snippets.impl;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.snippets.Extractor;
import ca.ualberta.odobot.snippets.SnippetExtractorService;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SnippetExtractorServiceImpl implements SnippetExtractorService {

    private static final Logger log = LoggerFactory.getLogger(SnippetExtractorServiceImpl.class);

    SqliteService sqliteService = null;

    List<DynamicXPath> xpaths = new ArrayList<>();

    private Vertx vertx;

    public SnippetExtractorServiceImpl(Vertx vertx, SqliteService sqliteService){
        this.vertx = vertx;
        this.sqliteService = sqliteService;
    }

    @Override
    public Future<Void> setDynamicXPaths(List<JsonObject> xpaths) {

        this.xpaths = xpaths.stream().map(DynamicXPath::fromJson).collect(Collectors.toList());
        log.info("{} Dynamic XPaths set!", this.xpaths.size());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> saveSnippets(JsonObject timelineEntityData) {

        List<Future> snippetFutures = new ArrayList<>();

        for(DynamicXPath xpath: xpaths){
            snippetFutures.add(Extractor.getSnippets(xpath, timelineEntityData));
        }

        return CompositeFuture.all(snippetFutures).compose(snippets->Future.succeededFuture());
    }
}
