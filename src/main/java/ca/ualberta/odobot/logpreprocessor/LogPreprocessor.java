package ca.ualberta.odobot.logpreprocessor;

import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.timeline.TimelineService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class LogPreprocessor extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(LogPreprocessor.class);

    private static final String API_PATH_PREFIX = "/api/*";
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8078;
    private static final String TIMELINE_SERVICE_ADDRESS = "timeline-service";
    private static final String ELASTICSEARCH_SERVICE_ADDRESS = "elasticsearch-service";
    private static final String TIMELINES_INDEX = "timelines";
    private static final String TIMELINE_ENTITIES_INDEX = "timeline-entities";

    private static TimelineService timelineService;
    private static ElasticsearchService elasticsearchService;

    Router mainRouter;
    Router api;
    HttpServer server;

    public Completable rxStart(){

        //Init Timeline Service
        //https://vertx.io/docs/vertx-service-proxy/java/#_exposing_your_service
        timelineService = TimelineService.create(vertx.getDelegate());
        new ServiceBinder(vertx.getDelegate())
                .setAddress(TIMELINE_SERVICE_ADDRESS)
                .register(TimelineService.class, timelineService);

        //Init Elasticsearch Service
        elasticsearchService = ElasticsearchService.create(vertx.getDelegate(), "localhost", 9200);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .register(ElasticsearchService.class, elasticsearchService);

        //Init Http Server
        HttpServerOptions options = new HttpServerOptions()
                .setHost(HOST)
                .setPort(PORT)
                .setSsl(false);

        server = vertx.createHttpServer(options);
        mainRouter = Router.router(vertx);
        api = Router.router(vertx);

        //Define API routes
        api.route().method(HttpMethod.GET).path("/timelines").handler(this::process);

        //Mount handlers to main router
        mainRouter.route().handler(LoggerHandler.create());
        mainRouter.route().handler(BodyHandler.create());
        mainRouter.route().handler(rc->{rc.response().putHeader("Access-Control-Allow-Origin", "*"); rc.next();});
        mainRouter.route(API_PATH_PREFIX).subRouter(api);

        server.requestHandler(mainRouter).listen(PORT);
        log.info("LogPreprocessor Server started on port: {}", PORT);

        return super.rxStart();
    }

    /**
     * Given a list of elastic search indices, process each of them into timelines
     * and return the JSON notation of the result.
     *
     * @param rc
     */
    private void process(RoutingContext rc){
        boolean isTransient = false; // Flag for whether to skip saving the resulting timeline in es.
        if(rc.request().params().contains("transient")){
            //Just some error handling
            if (rc.request().params().getAll("transient").size() > 1){
                rc.response().setStatusCode(400).end(new JsonObject().put("error", "cannot have multiple 'transient' parameters in request.").encode());
                return;
            }
            isTransient = Boolean.parseBoolean(rc.request().params().get("transient"));
        }

        final boolean _isTransient = isTransient;
       List<String> esIndices = rc.queryParam("index");
       //Fetch the event logs corresponding with every index requested.
        CompositeFuture.all(
               esIndices.stream().map(elasticsearchService::fetchAll)
               .collect(Collectors.toList())
       //Parse each list of events into its own timeline object.
       ).compose(mapper->{
           List<List<JsonObject>> eventLists = mapper.list();
           return CompositeFuture.all(
                   eventLists.stream().map(timelineService::parse)
                           .collect(Collectors.toList())
           );
       }).onSuccess(timelines->{
           JsonArray result = new JsonArray();
           List<JsonObject> resultObjects = new ArrayList<>();
           //Annotate the timelines with their corresponding ES indices & accumulate in result JsonArray.
           List<Timeline> timelineList = timelines.list();
           ListIterator<Timeline> it = timelineList.listIterator();
           while (it.hasNext()){
               var index = it.nextIndex();
               Timeline curr = it.next();
               curr.getAnnotations().put("origin-es-index", esIndices.get(index));
               JsonObject json = curr.toJson();
               result.add(json);
               //Embed annotations into the object that is being sent to es.
               resultObjects.add(json.put("annotations", curr.getAnnotations()));
           }

           rc.response().putHeader("Content-Type", "application/json").end(result.encode());

           if(!_isTransient){ //If this is not a transient request, save the timeline into elastic search
                final String timelinesString = resultObjects.stream().map(object->object.getString("id")).collect(StringBuilder::new, (sb, ele)->sb.append(ele + ", "), StringBuilder::append).toString();

                //Save the timelines themselves
               elasticsearchService.saveIntoIndex(resultObjects, TIMELINES_INDEX).onSuccess(saved->{
                   log.info("Timelines {} persisted in elasticsearch index: {}", timelinesString,TIMELINES_INDEX);
               }).onFailure(err->{
                   log.error("Error persisting timelines {} into elasticsearch index: {}",timelinesString, TIMELINES_INDEX);
                   log.error(err.getMessage(), err);
               });

               //Save the entities within the timeline in a separate index as well.

               //Extract the entities from inside the result json arrays into a single List<JsonObject>
               List<JsonObject> entities = resultObjects.stream().map(timelineJson->
                       timelineJson.getJsonArray("data").stream()
                               .map(o->(JsonObject)o)
                               .collect(Collectors.toList())

               ).collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);

               final String entitiesString = entities.stream().map(entity->entity.getString("id")).collect(StringBuilder::new, (sb, id)->sb.append(id + ", "), StringBuilder::append).toString();
               elasticsearchService.saveIntoIndex(entities, TIMELINE_ENTITIES_INDEX).onSuccess(saved->{
                   log.info("Entities {} persisted in elasticsearch index: {}",entitiesString,TIMELINE_ENTITIES_INDEX);
               }).onFailure(err->{
                   log.error("Error persisting entities {} into elastic search index: {}", entitiesString,TIMELINE_ENTITIES_INDEX);
               });
           }
       });

    }

}
