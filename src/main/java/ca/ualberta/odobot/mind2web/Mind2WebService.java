package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class Mind2WebService extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebService.class);

    Route modelConstructionPath;

    @Override
    public String serviceName() {
        return "Mind2Web Service";
    }

    @Override
    public String configFilePath() {
        return "config/mind2web.yaml";
    }

    public Completable onStart(){
        super.onStart();

        modelConstructionPath = api.route().method(HttpMethod.POST).path("/model");

        modelConstructionPath.handler(this::buildTraces);

        return Completable.complete();
    }

    private void buildTraces(RoutingContext rc){

        log.info("Hit build traces!");

        //Get the json traces from the request body
        JsonArray tasks = rc.body().asJsonArray();

        List<Trace> traces = tasks.stream()
                .map(o->(JsonObject)o)
                .map(Mind2WebUtils::processTask)
                .collect(Collectors.toList());

        log.info("Processed {} traces", traces.size());
        log.info("{} clicks", traces.stream().mapToLong(Trace::numClicks).sum());
        log.info("{} selects", traces.stream().mapToLong(Trace::numSelects).sum());
        log.info("{} types", traces.stream().mapToLong(Trace::numTypes).sum());
        log.info("{} total actions", traces.stream().mapToInt(Trace::size).sum());

        log.info("XPaths:");

        traces.stream().forEach(trace->trace.forEach(operation -> log.info("{}", operation.getTargetElementXpath())));

        rc.put("traces", traces);

        rc.next();
    }
}
