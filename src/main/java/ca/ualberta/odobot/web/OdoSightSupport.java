package ca.ualberta.odobot.web;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.LogParser;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import org.eclipse.rdf4j.query.algebra.In;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

public class OdoSightSupport extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OdoSightSupport.class);
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8079;
    private static final String SCRAPE_SCRIPT_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo.sh";

    private static final String SCRAPE_SCRIPT_V2_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo_v2.sh";
    private static final String SCRAPE_SCRIPT_V3_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo_v3.sh";
    private static final String DATABASE_CONTAINER_NAME = "canvas-lms-postgres-1";
    private static final String DATABASE_LOGS_PATH = "/var/lib/postgresql/data/log/";
    private static final String DATABASE_LOG_NAME_PREFIX = "db_log_";

    private static final String DATABASE_LOG_NAME_SUFFIX = ".csv";

    private SqliteService sqliteService;

    private ElasticsearchService elasticService;
    HttpServer server;
    Router router;

    WebClient client;
    @Override
    public Completable rxStart(){

        log.info("Starting Odo Sight Support Server at {}:{}", HOST, PORT);

        //Setup webclient
        client = WebClient.create(vertx.getDelegate());



        log.info("Initializing Sqlite Service Proxy");
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);
        elasticService = ElasticsearchService.createProxy(vertx.getDelegate(), ELASTICSEARCH_SERVICE_ADDRESS);

        HttpServerOptions options = new HttpServerOptions()
                .setHost(HOST)
                .setPort(PORT)
                .setSsl(false);

        server = vertx.createHttpServer(options);
        router = Router.router(vertx);

        //Define routes
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());
        router.route().handler(rc->{rc.response().putHeader("Access-Control-Allow-Origin", "*");rc.next();});
        router.route().method(HttpMethod.GET).path("/odo-sight/alive").handler(rc->rc.response().setStatusCode(200).end());
        //router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo").handler(this::scrapeAuditLogs);
        router.route().method(HttpMethod.POST).path("/odo-sight/bulk-scrape-mongo").handler(this::bulkScrape);
        router.route().method(HttpMethod.POST).path("/odo-sight/bulk-scrape-mongo/v2").handler(this::bulkScrapeV2);
        router.route().method(HttpMethod.POST).path("/odo-sight/bulk-scrape-mongo/v3").handler(this::bulkScrapeV3);
        router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo").handler(this::scrapeMongo);
        router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo/v2").handler(this::scrapeMongoV2);



        server.requestHandler(router).listen(PORT);

        return super.rxStart();
    }

    /**
     * This bulk scrape configures a logstash pipeline to scrape all provided flights into a target instance.
     * It differs from v2, which scrapes one flight at a time.
     * @param rc
     */
    public void bulkScrapeV3(RoutingContext rc){
        //Get the target esIndex where we will deposit the scraped data.
        String esIndex = rc.request().getParam("es-index");

        JsonArray flightsToScrape = rc.body().asJsonArray();
        Set<String> flightIds = flightsToScrape.stream().map(o->(JsonObject)o).map(flight->flight.getString("id")).collect(Collectors.toSet());

        String collectionsRegex = makeCollectionsRegex(flightIds);
        log.info("Computed collections regex for logstash pipeline: {}", collectionsRegex);

//        List<String> collectionRegexes = makeCollectionsRegex(flightIds, 100);
//        Iterator<String> it = collectionRegexes.iterator();
//        Future f = null;
//        while (it.hasNext()){
//            String currRegex = it.next();
//            if (f == null){
//                f = vertx.getDelegate().executeBlocking(blocking->scrapeV3(blocking, currRegex, esIndex));
//            }else{
//                f.compose(result->vertx.getDelegate().executeBlocking(blocking->scrapeV3(blocking, currRegex, esIndex)));
//            }
//
//        }






        vertx.executeBlocking(blocking->scrapeV3(blocking.getDelegate(), collectionsRegex, esIndex))
                .doAfterTerminate(()->log.info("Bulk scrape pipeline started!"))
                .subscribe();

        rc.response().setStatusCode(200).end();

    }

    /**
     * Like {@link #makeCollectionsRegex(Set), but returns a list of regexes such that each one contains no more than maxSize collections.
     *
     * This is necessary to avoid error 206 when invoking the mongo scrape script.
     *
     * @param flightIds a set of flightIds from which to create regexes
     * @param maxSize the maximum number of collections a single regex should represent.
     * @return
     */
    private List<String> makeCollectionsRegex(Set<String> flightIds, int maxSize){

        List<String> result = new ArrayList<>();

        Set<String> batch = new HashSet<>();

        Iterator<String> it = flightIds.iterator();
        while (it.hasNext()){
            batch.add(it.next());

            if(batch.size() == maxSize){
                result.add(makeCollectionsRegex(batch));
                batch.clear();
            }
        }

        //Handle any left over flights
        if(batch.size() > 0){
            result.add(makeCollectionsRegex(batch));
        }

        return result;

    }

    private String makeCollectionsRegex(Set<String> flightIds){

        StringBuilder sb = new StringBuilder();
        Iterator<String> it = flightIds.iterator();
        while (it.hasNext()){
            sb.append(it.next());
            if(it.hasNext()){
                sb.append("|");
            }
        }

        return sb.toString();
    }

    public void bulkScrapeV2(RoutingContext rc){
        //Get the target esIndex where we will deposit the scraped data.
        String esIndex = rc.request().getParam("es-index");
        Promise<Set<String>> excludePromise = Promise.promise();

        elasticService.getFlights(esIndex, "flight_name")
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(excludePromise::complete);

        JsonArray flightsToScrape = rc.body().asJsonArray();

        excludePromise.future()
                .onSuccess(
                        exclude->{

                            Iterator<JsonObject> it = flightsToScrape.stream().map(o->(JsonObject)o).iterator();
                            Future f = null;
                            while (it.hasNext()){
                                JsonObject flight = it.next();

                                if(!exclude.contains(flight.getString("name"))){
                                    log.info("{} is not in elasticsearch, scraping...", flight.getString("name"));
                                    if(f == null){
                                        f = vertx.getDelegate().executeBlocking(blocking->scrapeV2(blocking, flight.getString("name"), flight.getString("id"), esIndex));
                                    }else {
                                        f.compose(done->vertx.getDelegate().executeBlocking(blocking->scrapeV2(blocking, flight.getString("name"), flight.getString("id"), esIndex)));
                                    }
                                }else{
                                    log.info("{} is already in elastic search, skipping...", flight.getString("name"));
                                }
                            }

                            f.onSuccess(done->{
                                log.info("Finished bulk scrape!");
                                rc.response().setStatusCode(200).end("Finished bulk scrape!");
                            });
                            f.onFailure(err->{
                                log.error("Error during bulk scrape!");
                                rc.response().setStatusCode(500).end();
                            });

                        }
                );


    }

    public void bulkScrape(RoutingContext rc){
        //If a prefix is given, we first query elasticsearch to find all indices which have already been scraped!
        //We do not re-scrape these indices, to avoid duplication of existing documents.
        String prefix = rc.request().getParam("prefix");
        Promise<Set<String>> excludePromise = Promise.promise();
        if(prefix != null){
            elasticService.getAliases(prefix)
                    .onFailure(err->log.error(err.getMessage(),err))
                    .onSuccess(excludePromise::complete);
        }else{
            excludePromise.complete(Set.of()); //Pass in an empty set of excluded indices otherwise.
        }


        JsonArray flightsToScrape = rc.body().asJsonArray();

        excludePromise.future().onSuccess(
                exclude->{

                    Iterator<JsonObject> it = flightsToScrape.stream().map(o->(JsonObject)o).iterator();
                    Future f = null;
                    while(it.hasNext()){
                        JsonObject flight = it.next();

                        if(!exclude.contains(flight.getString("name"))){
                            log.info("{} is not in elasticsearch, scraping...", flight.getString("name"));
                            if(f == null){
                                f = vertx.getDelegate().executeBlocking(blocking->scrape(blocking, flight.getString("name"), flight.getString("id")));
                            }else{
                                f.compose(done->vertx.getDelegate().executeBlocking(blocking->scrape(blocking, flight.getString("name"), flight.getString("id"))));
                            }
                        }else{
                            log.info("{} is already in elastic search, skipping...", flight.getString("name"));
                        }

                    }

                    f.onSuccess(done->log.info("Finished bulk scrape!"));
                    f.onFailure(err->log.error("Error during bulk scrape!"));
                }
        );



    }

    public void scrapeMongoV2(RoutingContext rc){
        JsonObject data = rc.body().asJsonObject();

        String flightName = data.getString("flightName");
        String flightId = data.getString("flightId");
        String esIndex = data.getString("esIndex");

        if(flightId == null || flightName == null || esIndex == null){
            rc.response().setStatusCode(400).end("BAD REQUEST");
            return;
        }

        vertx.executeBlocking(blocking->scrapeV2(blocking.getDelegate(), flightName, flightId, esIndex)).doAfterTerminate(()->{
            log.info("Scrape script invoke complete!");
        }).subscribe();

        rc.response().setStatusCode(201).end();

    }

    public void scrapeMongo(RoutingContext rc){

        JsonObject data = rc.body().asJsonObject();

        String flightId = data.getString("flightId");
        String flightName = data.getString("flightName");


        if (flightId == null || flightName == null){
            rc.response().setStatusCode(400).end("BAD REQUEST");
            return;
        }

        vertx.rxExecuteBlocking(blocking->scrape(blocking.getDelegate(), flightName, flightId)).doAfterTerminate(()->{
            log.info("Scrape script invoke complete!");
        }).subscribe();


        rc.response().setStatusCode(201).end();


    }

    /**
     * Scrapes events from a flight into a specified elasticsearch index.
     * @param promise
     * @param mongoCollectionRegex a regex matching the mongo collections to scrape
     * @param esIndex the elastic search index into which the data should be indexed.
     */
    private void scrapeV3(Promise promise, String mongoCollectionRegex, String esIndex){
        try{
            log.info("Executing mongo scrape v3 script for {}  into es-index: {}", mongoCollectionRegex, esIndex);
            ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_V3_PATH, "\""+mongoCollectionRegex +"\"", esIndex);
            pb.inheritIO();
            Process scrapeProcess = pb.start();
            scrapeProcess.waitFor(15, TimeUnit.SECONDS);
            promise.complete();
        }catch (IOException | InterruptedException e){
            log.error(e.getMessage(), e);

            if(e.getMessage().contains("CreateProcess error=206")){
                log.error("Too many flights being scraped at once try splitting your request in half.");
            }

        }
    }

    /**
     * Scrapes events from a flight into a specified elasticsearch index.
     * @param promise
     * @param flightName the human readable name of the flight to scrape from mongo eg: 'selenium-test-ep-13'
     * @param flightId the uuid like flight id that is the collection name in mongo
     * @param esIndex the elastic search index into which the data should be indexed.
     */
    private void scrapeV2(Promise promise, String flightName, String flightId, String esIndex){
        try{
            log.info("Executing mongo scrape v2 script for {} ({}) into es-index: {}", flightId, flightName, esIndex);
            ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_V2_PATH, flightId, flightName, esIndex);
            pb.inheritIO();
            Process scrapeProcess = pb.start();
            scrapeProcess.waitFor(15, TimeUnit.SECONDS);
            Thread.sleep(60000);
            promise.complete();
        }catch (IOException | InterruptedException e){
            log.error(e.getMessage(), e);
        }
    }

    private void scrape( Promise promise, String flightName, String flightId){
        try{
            log.info("Executing mongo scrape for flightId: {} into es-index: {} ", flightId, flightName);
            ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_PATH, flightId, flightName);
//                ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_PATH, flightId, flightName,"&&","echo", "\"__END__\"");
            //ProcessBuilder pb = new ProcessBuilder("wsl", "ls;", "echo", "\"__END__\"");
            pb.inheritIO();
            Process scrapeProcess = pb.start();
            scrapeProcess.waitFor(15, TimeUnit.SECONDS);
            Thread.sleep(60000);
            promise.complete();
        } catch (IOException | InterruptedException ioException) {
            log.error(ioException.getMessage(), ioException);
        }
    }

    public void scrapeAuditLogs(RoutingContext rc){

        vertx.executeBlocking(blocking->{

            try{
                log.info("Fetching database audit logs from docker container!");

                SimpleDateFormat f = new SimpleDateFormat("EEE");
                String dayString = f.format(new Date());

                String src = DATABASE_CONTAINER_NAME + ":" + DATABASE_LOGS_PATH + DATABASE_LOG_NAME_PREFIX + dayString + DATABASE_LOG_NAME_SUFFIX;

                ProcessBuilder pb = new ProcessBuilder("wsl", "docker", "cp", src, "." );
                pb.inheritIO();
                Process scrapeProcess = pb.start();
                scrapeProcess.waitFor(15, TimeUnit.SECONDS);

                blocking.complete();
            } catch (IOException | InterruptedException ioException) {
                log.error(ioException.getMessage(), ioException);
            }



        }).doAfterTerminate(()->{
            log.info("Database audit logs retrieved!");
            log.info("Parsing!");

            SimpleDateFormat f = new SimpleDateFormat("EEE");
            String dayString = f.format(new Date());

            String logPath = DATABASE_LOG_NAME_PREFIX + dayString + DATABASE_LOG_NAME_SUFFIX;
            LogParser logParser = new LogParser(logEntry->sqliteService.insertLogEntry(logEntry.toJson()));
            logParser.parseDatabaseLogFile(logPath)
                    .onSuccess(done->log.info("Parsed {} database audit log entries", logParser.parseCount))
                    .onFailure(err->log.error(err.getMessage(), err));
            ;



        }).subscribe();
//
        rc.next();

        //rc.response().setStatusCode(201).end();
    }


}
