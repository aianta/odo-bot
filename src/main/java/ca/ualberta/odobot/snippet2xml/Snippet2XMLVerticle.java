package ca.ualberta.odobot.snippet2xml;

import ca.ualberta.odobot.common.HttpServiceVerticle;

import ca.ualberta.odobot.snippets.Snippet;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.serviceproxy.ServiceBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
                    .onSuccess(compositeFuture -> {

                        List<List<Snippet>> snippetSamples = compositeFuture.list();

                        snippetSamples.stream().limit(1).forEach(snippets->snippet2XML.makeSchema(snippets)
                                .onSuccess(schemaResult->{
                                    log.info("Schema Result:\n{}", schemaResult.encodePrettily());
                                })
                                .onFailure(err->log.error(err.getMessage(), err))

                        );


                    });

            rc.response().setStatusCode(200).end();

        });

        return Completable.complete();

    }


}
