package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.snippet2xml.SemanticSchema;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.sqlite.SqliteVectorService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceBinder;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class TaskPlannerVerticle extends HttpServiceVerticle {
    private static final Logger log = LoggerFactory.getLogger(TaskPlannerVerticle.class);

    public static TaskPlannerService service;

    public static SqliteService sqliteService;

    public static SqliteVectorService sqliteVectorService;

    public static Neo4JUtils neo4j;

    @Override
    public String serviceName() {
        return "Task Planner Service";
    }

    @Override
    public String configFilePath() {
        return "config/taskplanner.yaml";
    }

    public Completable onStart(){
        super.onStart();

        JsonObject neo4jConfig = _config.getJsonObject("neo4j");

        neo4j = new Neo4JUtils(neo4jConfig.getString("host"), neo4jConfig.getString("user"), neo4jConfig.getString("password"));


        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        sqliteVectorService = SqliteVectorService.createProxy(vertx.getDelegate(), SQLITE_VECTOR_SERVICE_ADDRESS);

        //Init and expose TaskPlanner Service
        service = TaskPlannerService.create(vertx.getDelegate(), _config, sqliteService, sqliteVectorService,  neo4j);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(TASK_PLANNER_SERVICE_ADDRESS)
                .register(TaskPlannerService.class, service);



        api.route().method(HttpMethod.POST).path("/computePath").handler(this::computePathHandler);
        api.route().method(HttpMethod.POST).path("/task").handler(this::taskHandler); //Process natural language task

        return Completable.complete();
    }

    @Deprecated
    /**
     * TODO: This method still uses the old schema parameter approach... update to resource params
     */
    private void taskHandler(RoutingContext rc){
        String taskDescription = rc.body().asString();

        Consumer<JsonObject> printObject = (json)->log.info("{}", json.encodePrettily());


        Future.all(
                service.getInputParameterMappings(taskDescription),
                service.getRelevantObjectParameters(taskDescription),
                service.getRelevantAPICalls(taskDescription)
        ).onSuccess(compositeFuture->{

            List<JsonObject> inputParameterMappings = compositeFuture.resultAt(0);
            List<JsonObject> objectParameters = compositeFuture.resultAt(1);
            List<JsonObject> apiCalls = compositeFuture.resultAt(2);



            log.info("Input Parameter Mappings:");
            inputParameterMappings.forEach(printObject);

            log.info("Object Parameters:");
            objectParameters.stream().forEach(printObject);

            log.info("API Calls:");
            apiCalls.forEach(printObject);

            JsonObject response =  new JsonObject();
            response.put("userLocation", "http://localhost:8088/login/canvas");
            response.put("targets", apiCalls.stream().map(apiCall->apiCall.getString("id")).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));

            //Compute input parameters in task format for odobot.
            JsonArray parameters = inputParameterMappings.stream()
                    .map(inputParam->{
                        JsonObject _param = new JsonObject()
                                .put("id", inputParam.getString("id"))
                                .put("type", "InputParameter")
                                .put("value", inputParam.getString("value"));
                        return _param;
                    }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            //Add schema/object parameters
            parameters.addAll(
                objectParameters.stream()
                        .map(objectParam->{
                            JsonObject _param = new JsonObject()
                                    .put("id", objectParam.getString("id"))
                                    .put("type", "SchemaParameter")
                                    .put("query", objectParam.getString("query"));
                            return _param;
                        }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll)
            );

            response.put("parameters", parameters);

            rc.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(response.encodePrettily());

        }).onFailure(err->{
            log.error(err.getMessage(), err);
            rc.response().setStatusCode(500).end(err.getMessage());
        })

        ;

    }

    private void computePathHandler(RoutingContext rc){

        JsonObject body = rc.body().asJsonObject();

        String startingNodeId = body.getString("startingNodeId");
        Set<String> inputParameters = body.getJsonObject("inputParameters").stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> apiCalls = body.getJsonArray("apiCalls").stream().map(o->(String)o).collect(Collectors.toSet());
        Set<String> objectParameters = body.getJsonArray("objectParameters").stream().map(o->(String)o).collect(Collectors.toSet());

        Transaction tx = LogPreprocessor.graphDB.db.beginTx();

        NavPathsConstructor constructor = new NavPathsConstructor(null, sqliteService);


        List<NavPath> result = constructor.construct(tx, startingNodeId, objectParameters, inputParameters, apiCalls);

        log.info("Found {} paths", result.size());
        NavPath.printNavPaths(result, 20);

        rc.response().setStatusCode(200).end();

    }
}
