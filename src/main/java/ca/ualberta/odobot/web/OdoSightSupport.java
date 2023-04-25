package ca.ualberta.odobot.web;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OdoSightSupport extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OdoSightSupport.class);
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8079;
    private static final String SCRAPE_SCRIPT_PATH = "/home/aianta/shock_and_awe/es-local/scrape_mongo.sh";

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



}
