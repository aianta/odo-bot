package ca.ualberta.odobot.taskplanner.impl;

import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.NavPathsConstructor;
import ca.ualberta.odobot.taskplanner.TaskPlannerService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class TaskPlannerServiceImpl implements TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerServiceImpl.class);

    private Vertx vertx;

    private JsonObject config;

    public TaskPlannerServiceImpl(Vertx vertx, JsonObject config){
        this.vertx = vertx;
        this.config = config;
    }

    @Override
    public Future<Void> computePath(String startingVertexId, Map<String, String> inputParameters, List<String> apiCalls) {

        Transaction tx = LogPreprocessor.graphDB.db.beginTx();



        return null;
    }
}
