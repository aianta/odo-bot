package ca.ualberta.odobot.web;

import ca.ualberta.odobot.sqlite.LogParser;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.Promise;
import io.vertx.rxjava3.core.Vertx;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OdoSightSupport extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OdoSightSupport.class);
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8079;
    private static final String SCRAPE_SCRIPT_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo.sh";
    private static final String DATABASE_CONTAINER_NAME = "canvas-lms-postgres-1";
    private static final String DATABASE_LOGS_PATH = "/var/lib/postgresql/data/log/";
    private static final String DATABASE_LOG_NAME_PREFIX = "db_log_";

    private static final String DATABASE_LOG_NAME_SUFFIX = ".csv";

    HttpServer server;
    Router router;

    @Override
    public Completable rxStart(){

        log.info("Starting Odo Sight Support Server at {}:{}", HOST, PORT);

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
        router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo").handler(this::scrapeAuditLogs);
        router.route().method(HttpMethod.POST).path("/odo-sight/scrape-mongo").handler(this::scrapeMongo);


        server.requestHandler(router).listen(PORT);

        return super.rxStart();
    }

    public void scrapeMongo(RoutingContext rc){
        JsonObject data = rc.body().asJsonObject();
        String flightId = data.getString("flightId");
        String flightName = data.getString("flightName");

        if (flightId == null || flightName == null){
            rc.response().setStatusCode(400).end("BAD REQUEST");
            return;
        }

        vertx.rxExecuteBlocking(blocking->{
            try{
                log.info("Executing mongo scrape for flightId: {} into es-index: {} ", flightId, flightName);
                ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_PATH, flightId, flightName);
//                ProcessBuilder pb = new ProcessBuilder("wsl", SCRAPE_SCRIPT_PATH, flightId, flightName,"&&","echo", "\"__END__\"");
                //ProcessBuilder pb = new ProcessBuilder("wsl", "ls;", "echo", "\"__END__\"");
                pb.inheritIO();
                Process scrapeProcess = pb.start();
                scrapeProcess.waitFor(15, TimeUnit.SECONDS);

                blocking.complete();
            } catch (IOException | InterruptedException ioException) {
                log.error(ioException.getMessage(), ioException);
            }
        }).doAfterTerminate(()->{
            log.info("Scrape script invoke complete!");
        }).subscribe();


        rc.response().setStatusCode(201).end();


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
            LogParser logParser = new LogParser(logEntry->log.info("{}", logEntry.toJson().encodePrettily()));
            logParser.parseDatabaseLogFile(logPath);


        }).subscribe();
//
//        rc.next();

        rc.response().setStatusCode(201).end();
    }


}
