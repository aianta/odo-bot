package ca.ualberta.odobot.cleaner;

import ca.ualberta.odobot.cleaner.impl.TagAndAttributeStrategy;
import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

/**
 * Exposes HTML cleaning functionality.
 */
public class CleanerVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(CleanerVerticle.class);

    @Override
    public String serviceName() {
        return "CleanerService";
    }

    @Override
    public String configFilePath() {
        return "config/cleaner.yaml";
    }

    public static SqliteService sqliteService;
    public static ElasticsearchService elasticsearchService;

    private CleanerService cleanerService;

    @Override
    public Completable onStart() {

        super.onStart();

        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        //Init ElasticSearch Service Proxy
        elasticsearchService =  new ServiceProxyBuilder(vertx.getDelegate())
                .setOptions(new DeliveryOptions().setSendTimeout(300000))
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .build(ElasticsearchService.class);

        cleanerService = CleanerService.create(vertx.getDelegate(), _config, new TagAndAttributeStrategy());

        api.route().method(HttpMethod.GET).path("/sampleDOMSnapshot").handler(this::sampleDOMSnapshot);
        api.route().method(HttpMethod.POST).path("/clean").handler(this::clean);
        api.route().method(HttpMethod.POST).path("/nodeLinks").handler(this::nodeLinks);

        return Completable.complete();

    }

    /**
     * Returns a single DOMSnapshot from the provided elasticsearch index.
     * @param rc
     */
    private void sampleDOMSnapshot(RoutingContext rc) {
        String esIndex = rc.queryParam("index").get(0);
        String eventType = rc.queryParam("eventDetails_name").get(0);

        elasticsearchService.fetchAll(esIndex).onSuccess(events -> {
            log.info("got events");
            Iterator<JsonObject> it = events.iterator();
            JsonObject event = it.next();
            //Randomly look for an event that has the specified name and contains a DOMSnapshot
            while (!event.containsKey("eventDetails_domSnapshot") &&  event.getString("eventDetails_name").equals(eventType)) {
                log.info("contains eventDetails_domSnapshot: {}", event.containsKey("eventDetails_domSnapshot"));
                log.info("eventDetails_name: {}", event.getString("eventDetails_name"));
                event = it.next();
            }

            JsonObject data = new JsonObject(event.getString("eventDetails_domSnapshot"));
            rc.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(data.getString("outerHTML"));

        }).onFailure(throwable -> log.error(throwable.getMessage(), throwable));
    }

    private void nodeLinks(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.toNodeLinks(html)
                .onSuccess(graph -> rc.response().setStatusCode(200).end(graph.encodePrettily()))
                .onFailure(throwable -> log.error(throwable.getMessage(), throwable));
        ;
    }

    private void clean(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.cleanHTML(html)
                .onSuccess(result -> rc.response().setStatusCode(200).end(result))
                .onFailure(ex -> rc.response().setStatusCode(500).end(ex.getMessage()));
        ;

    }
}
