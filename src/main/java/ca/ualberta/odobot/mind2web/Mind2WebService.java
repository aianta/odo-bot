package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.NavNode;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class Mind2WebService extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebService.class);

    Neo4JUtils neo4j;

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

        neo4j = new Neo4JUtils("bolt://localhost:7687", "neo4j", "odobotdb");

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

        log.info("Constructing nav model from traces!");

        traces.stream().forEach(this::buildNavModel);

        rc.put("traces", traces);

        rc.next();
    }



    public void buildNavModel(Trace trace){

        log.info("Inserting trace {} into nav model. ", trace.getAnnotationId());

        ListIterator<Operation> it = trace.listIterator();

        //Do one pass through the trace to create/update all required nodes.
        while (it.hasNext()){

            Operation op = it.next();

            if(op instanceof Click){
                neo4j.processClick((Click) op);
            }

            if(op instanceof Type){
                neo4j.processType((Type) op);
            }

            if(op instanceof SelectOption){
                neo4j.processSelectOption((SelectOption) op);
            }

        }

        //Now do another pass to connect everything
        if(trace.size() > 2){
            it = trace.listIterator();
            ListIterator<Operation> successorIt = trace.listIterator();
            successorIt.next();

            while (it.hasNext() && successorIt.hasNext()){
                Operation curr = it.next();
                Operation next = successorIt.next();

                NavNode a = neo4j.resolveNavNode(curr);
                NavNode b = neo4j.resolveNavNode(next);

                neo4j.bind(a, b);
            }


        }else if(trace.size() == 2){

            NavNode a = neo4j.resolveNavNode(trace.get(0));
            NavNode b = neo4j.resolveNavNode(trace.get(1));

            neo4j.bind(a,b);

        }else{
            log.info("Trace size: {}", trace.size());
            throw new RuntimeException("Trace is too small to model!");
        }

    }
}
