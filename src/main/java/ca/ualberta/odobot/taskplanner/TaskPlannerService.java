package ca.ualberta.odobot.taskplanner;

import ca.ualberta.odobot.taskplanner.impl.TaskPlannerServiceImpl;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

@ProxyGen
public interface TaskPlannerService {

    static TaskPlannerService create(Vertx vertx, JsonObject config){
        return new TaskPlannerServiceImpl(vertx, config);
    }

    static TaskPlannerService createProxy(Vertx vertx, String address){
        return new TaskPlannerServiceVertxEBProxy(vertx, address);
    }

    /**
     *
     * @param startingVertexId UUID of the vertex in the navigational model from which paths should begin.
     * @param inputParameters A map of input parameter vertex UUIDs and associated string values.
     * @param apiCalls A list of api call vertex IDs which should be targets for the computed path.
     * @return
     */
    Future<Void> computePath(String startingVertexId, Map<String, String> inputParameters, List<String> apiCalls);
}
