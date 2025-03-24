package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.RoutingContext;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

public class TaskPlannerVerticle extends HttpServiceVerticle {
    private static final Logger log = LoggerFactory.getLogger(TaskPlannerVerticle.class);

    public static TaskPlannerService service;

    public static SqliteService sqliteService;

    private static Neo4JUtils neo4j;

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

        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);


        JsonObject neo4jConfig = _config.getJsonObject("neo4j");

        neo4j = new Neo4JUtils(neo4jConfig.getString("host"), neo4jConfig.getString("user"), neo4jConfig.getString("password"));

        api.route().method(HttpMethod.POST).path("/computePath").handler(this::computePathHandler);

        return Completable.complete();
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

    }
}
