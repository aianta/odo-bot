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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

public class OdoSightSupport extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OdoSightSupport.class);
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8079;
    private static final String SCRAPE_SCRIPT_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo.sh";
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
        router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo").handler(this::scrapeMongo);


        server.requestHandler(router).listen(PORT);

        return super.rxStart();
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
