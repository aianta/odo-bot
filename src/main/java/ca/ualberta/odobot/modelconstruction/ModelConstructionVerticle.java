package ca.ualberta.odobot.modelconstruction;

import ca.ualberta.odobot.modelconstruction.impl.TagAndAttributeStrategy;
import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import io.vertx.rxjava3.ext.web.RoutingContext;

import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

/**
 * Exposes HTML cleaning functionality.
 */
public class ModelConstructionVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ModelConstructionVerticle.class);

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

    private static final String ODO_LSH_HOST = "172.29.71.50";

    private WebClient webClient;

    private CleanerService cleanerService;

    @Override
    public Completable onStart() {

        super.onStart();

        WebClientOptions webClientOptions = new WebClientOptions()
                .setUserAgent("OdoBot");
        webClient = WebClient.create(vertx.getDelegate(), webClientOptions);

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
        api.route().method(HttpMethod.GET).path("/buildStateClusters").handler(this::buildStateClusters);
        api.route().method(HttpMethod.GET).path("/loadDOMSnapshots").handler(this::loadDOMSnapshots);

        return Completable.complete();

    }

    private Predicate<JsonObject> stateClusteringEventFilter(){
        return (event)->
             event.containsKey("eventDetails_domSnapshot") &&
                    event.getString("eventDetails_name") != null &&
                    event.containsKey("eventDetails_name") &&
                    !event.getString("eventDetails_name").equals("NETWORK_EVENT") &&
                    !event.getString("eventDetails_name").equals("DOM_EFFECT");

    }

    private void loadDOMSnapshots(RoutingContext rc){
        String sourceIndex = rc.queryParam("srcIndex").get(0);

        String eventBusAddress = UUID.randomUUID().toString();
        MessageConsumer messageConsumer = vertx.eventBus().consumer(eventBusAddress, msg->{
            JsonObject event = (JsonObject) msg.body();
            if(stateClusteringEventFilter().test(event)){

                JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                cleanerService.cleanHTML(domSnapshotData.getString("outerHTML"))
                        .onSuccess(cleanedHTML->{
                            sqliteService.saveDOMSnapshot(
                                    event.getString("mongo_id"),
                                    cleanedHTML,
                                    baseURI,
                                    sourceIndex

                            )
                                    .onSuccess(done->log.info("Saved {} to Sqlite", event.getString("mongo_id")))
                                    .onFailure(err->log.error("Error saving DOMSnapshot to SQLite. " + err.getMessage(), err ));
                        });
            }
        });

        elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(done->{
                    messageConsumer.getDelegate().unregister();
                });
    }

    private void buildStateClusters(RoutingContext rc){

        String sourceIndex = rc.queryParam("srcIndex").get(0);
        String lshName = rc.queryParam("targetLSH").get(0);
        Set<String> exclude = Set.of("NETWORK_EVENT", "DOM_EFFECT");

        //Create an LSH model for clustering the DOMSnapshots.
        webClient
                .post(5000, ODO_LSH_HOST, "/minhashLSH")
                .sendJson(new JsonObject()
                        .put("threshold", 0.9)
                        .put("name", lshName)
                        .put("num_perm", 256)
                ).compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(response->{
                    String lshId = response.getString("id");
                    log.info("New LSH created with id {}", lshId);

                    //Register an eventbus listener to process the documents/events from elasticsearch.
                    String eventBusAddress = "%s-hashingProcessor".formatted(lshId);

                    MessageConsumer eventBusListener = vertx.eventBus().consumer(eventBusAddress, msg->{
                        JsonObject event = (JsonObject) msg.body();
                        if(stateClusteringEventFilter().test(event)){

                            JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                            String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                            cleanerService.toNodeLinks(domSnapshotData.getString("outerHTML"))
                                    .compose(nodeLinks->{
                                        nodeLinks.put("id", event.getString("mongo_id"));
                                        nodeLinks.put("baseURI", baseURI==null?"-":baseURI);

                                        return webClient.put(5000, ODO_LSH_HOST, "/minhashLSH/" + lshId)
                                                .sendJson(new JsonArray().add(nodeLinks));
                                    });

                        }
                    });

                    elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                            .onFailure(err->log.error(err.getMessage(), err))
                            .onSuccess(done->{
                                log.info("Done processing events, unregistering event bus listener!");
                                eventBusListener.getDelegate().unregister();
                            });


                });

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
