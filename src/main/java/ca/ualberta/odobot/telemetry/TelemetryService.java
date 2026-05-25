package ca.ualberta.odobot.telemetry;

import ca.ualberta.odobot.telemetry.impl.TelemetryServiceImpl;
import ca.ualberta.odobot.telemetry.model.ExperimentResults;
import ca.ualberta.odobot.telemetry.model.TaskInstanceResults;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ProxyGen
public interface TelemetryService {

    static TelemetryService create(Vertx vertx, JsonObject config) {
        return new TelemetryServiceImpl(vertx, config);
    }

    static TelemetryService createProxy(Vertx vertx, String address){
        return new TelemetryServiceVertxEBProxy(vertx, address);
    }

    Future<Void> reportExperimentResults(ExperimentResults results);

    Future<Void> reportTaskResults(TaskInstanceResults results);

}
