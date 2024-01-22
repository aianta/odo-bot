package ca.ualberta.odobot.tpg;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.service.TPGService;
import io.reactivex.rxjava3.core.Completable;


import io.vertx.core.CompositeFuture;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.AbstractVerticle;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.ext.web.handler.LoggerHandler;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

/**
 * @author Alexandru Ianta
 * A wrapper verticle for the TPG service. Exposes TPG operations as a web API.
 */
public class TPGVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TPGVerticle.class);

    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8077;
    private static final String API_PATH_PREFIX = "/api/*";

    private TPGService tpgService;
    private ElasticsearchService elasticsearchService;
    private SqliteService db;

    HttpServer server;
    Router mainRouter;

    Router api;


    public Completable rxStart(){
        try{

            //Initialize Elasticsearch Service proxy
            ServiceProxyBuilder elasticsearchServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                    .setAddress(ELASTICSEARCH_SERVICE_ADDRESS);
            elasticsearchService = elasticsearchServiceProxyBuilder.build(ElasticsearchService.class);

            //Initalize TPGService
            tpgService = TPGService.create(elasticsearchService);
            new ServiceBinder(vertx.getDelegate())
                    .setAddress(TPG_SERVICE_ADDRESS)
                    .register(TPGService.class, tpgService);

            //Initialize SQLite Service proxy
            ServiceProxyBuilder dbServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                    .setAddress(SQLITE_SERVICE_ADDRESS);
            db = dbServiceProxyBuilder.build(SqliteService.class);

            //Set up http server
            HttpServerOptions options = new HttpServerOptions()
                    .setHost(HOST)
                    .setPort(PORT)
                    .setSsl(false);

            server = vertx.createHttpServer(options);
            mainRouter = Router.router(vertx);
            api = Router.router(vertx);

            api.route(HttpMethod.POST,"/train").handler(this::loadDataset);
            api.route(HttpMethod.POST, "/train").handler(this::trainHandler);
            api.route(HttpMethod.POST, "/identify").handler(this::loadDataset);
            api.route(HttpMethod.POST, "/identify").handler(this::identifyHandler);

            mainRouter.route().handler(LoggerHandler.create());
            mainRouter.route().handler(BodyHandler.create());
            mainRouter.route().handler(rc->{
                rc.response().putHeader("Access-Control-Allow-Origin", "*");
                rc.next();
            });
            mainRouter.route(API_PATH_PREFIX).subRouter(api);

            server.requestHandler(mainRouter).listen(PORT);

            log.info("TPG Verticle started, API available on port {}", PORT);


        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return super.rxStart();
    }

    private void identifyHandler(RoutingContext rc){
        JsonObject identifyConfig = rc.body().asJsonObject();

        List<TrainingExemplar> exemplars = rc.get("dataset");

        CompositeFuture.all(exemplars.stream().map(exemplar->tpgService.identify(identifyConfig, exemplar.toJson()))
                        .collect(Collectors.toList()))
                        .onSuccess(done->{
                            log.info("Identified all!");
                            rc.response().setStatusCode(200).end();
                        });


    }

    /**
     * Fetches a dataset from the database and stores its exemplars in the routing
     * context for future use.
     * @param rc
     */
    private void loadDataset(RoutingContext rc){
        String datasetName = rc.request().getParam("dataset");
        log.info("Attempting to load {} dataset", datasetName);

        db.loadTrainingDataset(datasetName).onSuccess(datasetJson->{
            rc.put("datasetJson", datasetJson);

            List<TrainingExemplar> trainingDataset = datasetJson.stream()
                    .map(o->(JsonObject)o)
                    .map(TrainingExemplar::fromJson)
                    .collect(Collectors.toList());

            rc.put("dataset", trainingDataset);

            if(trainingDataset.size() == 0){
                rc.response().setStatusCode(400).end("No training exemplars found for specified dataset!");
            }else{
                rc.next();
            }

        });
    }

    private void trainHandler(RoutingContext rc){
        JsonObject trainingTaskConfig = rc.body().asJsonObject();
        tpgService.train(trainingTaskConfig, rc.get("datasetJson"))
                .onSuccess(done->{
                    log.info("Training complete!");
                    log.info("{}", done.encodePrettily());

                });

        rc.response().setStatusCode(200).end();
    }

}
