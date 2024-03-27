package ca.ualberta.odobot.tpg;

import ca.ualberta.odobot.domsequencing.DOMSequencingService;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.impl.TrainingExemplar;
import ca.ualberta.odobot.tpg.service.TPGService;
import io.reactivex.rxjava3.core.Completable;


import io.vertx.core.CompositeFuture;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
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

import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;
import static ca.ualberta.odobot.tpg.service.impl.TrainingTaskImpl.generateActions;
import static ca.ualberta.odobot.tpg.service.impl.TrainingTaskImpl.generateHumanReadableActions;

/**
 * @author Alexandru Ianta
 * A wrapper verticle for the TPG service. Exposes TPG operations as a web API.
 */
public class TPGVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TPGVerticle.class);

    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8027;
    private static final String API_PATH_PREFIX = "/api/*";

    private TPGService tpgService;
    private ElasticsearchService elasticsearchService;
    private SqliteService db;

    private DOMSequencingService domSequencingService;

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

            ServiceProxyBuilder domSequencingServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                    .setAddress(DOMSEQUENCING_SERVICE_ADDRESS);
            domSequencingService = domSequencingServiceProxyBuilder.build(DOMSequencingService.class);

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
            api.route(HttpMethod.POST, "/dehash").handler(this::dehashHandler);

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

    private void dehashHandler(RoutingContext rc){
        JsonObject body  = rc.body().asJsonObject();
        List<Integer> indexedLocations = body.getJsonArray("indexedLocations").stream().map(i->(Integer)i).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        domSequencingService.htmlToSequence(body.getString("outerHTML")).onSuccess(result->{
            List<String> masked = new ArrayList<>();
            List<String> sequences = result.stream().map(o->(String)o).collect(Collectors.toList());
            ListIterator<String> it = sequences.listIterator();
            while (it.hasNext()){
                String curr = it.next();
                if(indexedLocations.contains(it.previousIndex())){
                    masked.add(curr);
                }else{
                    masked.add("-");
                }
            }

            JsonObject finalResult = new JsonObject()
                    .put("original", result)
                            .put("masked", masked.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

            rc.response().setStatusCode(200).end(finalResult.encode());
        });
    }

    private void identifyHandler(RoutingContext rc){
        JsonObject identifyConfig = rc.body().asJsonObject();

        List<TrainingExemplar> exemplars = rc.get("dataset");

        //Compute the path actions and a map that converts long labels into human readable strings.
        //TODO -> this is a mess, we have to convert the pathActions to a List, and the actionsMap to a JSON object to conform to vertx service proxy restrictions
        //  But really we shouldn't be doing this at all, and need to re-organize things such that this isn't necessary. This is a temporary hack.
        long [] pathActions = generateActions(exemplars, 0);
        log.info("{} path actions found in dataset", pathActions.length);
        List<Long> pathActionsList = Arrays.stream(pathActions).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        Map<Long,String> humanReadablePathActions = generateHumanReadableActions(exemplars);

        JsonObject actionsMap = new JsonObject();
        humanReadablePathActions.forEach((longVal, label)->actionsMap.put(longVal.toString(), label));


        //TODO - temporarily for testing
        List<TrainingExemplar> subset = exemplars.stream().limit(5).toList();

        CompositeFuture.all(subset.stream().map(exemplar->tpgService.identify(identifyConfig, exemplar.toJson(), pathActionsList, actionsMap))
                        .collect(Collectors.toList()))
                        .onSuccess(done->{
                            JsonArray results = done.list().stream().map(o->(JsonObject)o).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                            log.info("Identified all!");
                            rc.response().setStatusCode(200).end(results.encodePrettily());
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
