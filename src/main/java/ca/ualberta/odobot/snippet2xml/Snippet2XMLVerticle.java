package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.common.HttpServiceVerticle;

import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class Snippet2XMLVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Snippet2XMLVerticle.class);

    @Override
    public String serviceName() {
        return "Snippet2XML Service";
    }

    @Override
    public String configFilePath() {
        return "config/snippet2xml.yaml";
    }


    public static SqliteService sqliteService;

    public static Snippet2XMLService snippet2XML;

    public Completable onStart(){
        super.onStart();

        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        //Init and expose Snippet2XML Service
        snippet2XML = Snippet2XMLService.create(vertx.getDelegate(), _config, Strategy.OPENAI);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(SNIPPET_2_XML_SERVICE_ADDRESS)
                .register(Snippet2XMLService.class, snippet2XML);

        api.route().method(HttpMethod.GET).path("/schemas").handler(rc->{

            sqliteService.getUniqueDynamicXpathsFromSnippets()
                    .compose(dxpaths->{

                        int samples = _config.getJsonObject("openAI").getJsonObject("makeSchema").getInteger("samples");

                        // Go fetch 3*samples of each
                        return Future.all(
                        dxpaths.stream()
                                        .map(dxpath->sqliteService.sampleSnippetsForDynamicXpath(samples*3, dxpath))
                                .collect(Collectors.toList()));
                    })
                    .compose(compositeFuture -> {
                        List<List<Snippet>> snippetSamples = compositeFuture.list();

                        return Future.join(
                                snippetSamples.stream().map(snippets->snippet2XML.makeSchema(snippets).otherwiseEmpty())
                                        .collect(Collectors.toList())
                        );

                    }).compose(compositeFuture -> {

                        log.info("Got makeSchema results!");

                        List<JsonObject> results = compositeFuture.list().stream().filter(Objects::nonNull).map(o->(JsonObject)o).collect(Collectors.toList());

                        //Extract semantic schemas and objects from makeSchemaResult, they are both contained in the response json.
                        List<SemanticSchema> schemas = new ArrayList<>();
                        List<SemanticObject> objects = new ArrayList<>();



                        results.forEach(e->{
                            //Use helper methods from Snippet2XMLService to extract
                            SemanticSchema schema = Snippet2XMLService.extractNewSchema(e);
                            schemas.add(schema);
                            objects.addAll(Snippet2XMLService.extractSemanticObjects(e, schema));
                        });

                        //Save all extracted schemas and objects.
                        schemas.forEach(sqliteService::saveSemanticSchema);
                        objects.forEach(sqliteService::saveSemanticObject);


                        return Future.all(
                                Future.succeededFuture(schemas),
                                Future.succeededFuture(objects),
                                sqliteService.getSnippets()
                        );
                    }).compose(compositeFuture->{

                        List<SemanticSchema> schemas = compositeFuture.resultAt(0);
                        List<SemanticObject> objects = compositeFuture.resultAt(1);
                        List<Snippet> snippets = compositeFuture.resultAt(2);
                        snippets = snippets.stream().collect(Collectors.toList());

                        log.info("Generated {} schemas", schemas.size());

                        log.info("Generating XML objects for {} snippets", snippets.size());

                        //Now let's make objects for all snippets
                        //TODO: one day, maybe we can re-use/avoid recomputing the

                        Boolean schemaOnlyFlag = Boolean.parseBoolean(rc.request().getParam("schemaOnly", "true"));

                        if(schemaOnlyFlag){
                            //Skip Semantic Object generation if schemaOnly flag is set.
                            return Future.succeededFuture();
                        }

                        return Future.join(snippets.stream()
                                .map(snippet -> {

                                    // Skip snippets that have already been processed as part of the schema generation process.
                                    if(objects.stream().map(SemanticObject::getSnippetId).collect(Collectors.toSet()).contains(snippet.getId())){
                                        log.info("XML object for snippet {} was already created during schema generation, skipping...", snippet.getId().toString());
                                        return null;
                                    };

                                    //Find the corresponding schema
                                    SemanticSchema targetSchema = schemas.stream().filter(schema -> schema.getDynamicXpathId().equals(snippet.getDynamicXpath())).findFirst().get();

                                    if(targetSchema == null){ //Handle the case where it's not found.
                                        log.info("Could not find matching semantic schema for dxpath: {}", snippet.getDynamicXpath());
                                        //If no schema could be found return
                                        return null;
                                    }

                                    return snippet2XML.getObjectFromSnippet(snippet, targetSchema).otherwiseEmpty();
                                })
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList()));
                    }).compose(compositeFuture -> {

                        if(compositeFuture.list() == null){
                            return Future.succeededFuture();
                        }

                        //have to filter nulls because of otherwiseEmpty
                        List<SemanticObject> semanticObjects = compositeFuture.list().stream().filter(Objects::nonNull).map(o->(SemanticObject)o).collect(Collectors.toList());
                        log.info("Done generating {} XML objects, saving objects to database!", semanticObjects.size());
                        semanticObjects.stream().map(SemanticObject::toJson).map(JsonObject::encodePrettily).forEach(log::info);
                        return Future.all(semanticObjects.stream().map(sqliteService::saveSemanticObject).collect(Collectors.toList()));
                    })
                    .onSuccess(done->{
                        log.info("Done generating XML schemas and objects!");
                    })
                    .onFailure(err->{
                        log.error(err.getMessage(), err);
                    })

            ;



            rc.response().setStatusCode(200).end();

        });

        return Completable.complete();

    }


}
