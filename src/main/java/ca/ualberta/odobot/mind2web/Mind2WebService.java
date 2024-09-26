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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class Mind2WebService extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebService.class);

    Neo4JUtils neo4j;

    Route modelConstructionRoute;

    @Override
    public String serviceName() {
        return "Mind2Web Service";
    }

    @Override
    public String configFilePath() {
        return "config/mind2web.yaml";
    }

    /**
     * Define the model construction path.
     * When registering the route in {@link #onStart()} we simply use {@link #MODEL_CONSTRUCTION_PATH},
     * but for rerouting requests when processing multiple data files we use {@link #getFullModelConstructionRoutePath()} to compute the full
     * path including the api prefix defined in {@link #configFilePath()}.
     *
     * By setting the model construction path through this final static string field we ensure the re-route functionality doesn't break when changes are made to the path.
     */
    private static final String MODEL_CONSTRUCTION_PATH = "/model";

    public Completable onStart(){
        super.onStart();

        modelConstructionRoute = api.route().method(HttpMethod.POST).path(MODEL_CONSTRUCTION_PATH);

        modelConstructionRoute.handler(this::loadDataSetup); //Figure out what/how to load
        modelConstructionRoute.handler(this::loadDataFromFile); //Load
        modelConstructionRoute.handler(this::buildTraces); //Build traces from loaded data

        neo4j = new Neo4JUtils("bolt://localhost:7687", "neo4j", "odobotdb");

        return Completable.complete();
    }

    private void loadDataSetup(RoutingContext rc){

        if(!(boolean)rc.get("loadDataSetup", false)){ //If data setup has not yet been performed

            //Setup routing context for data loading.
            List<String> dataFiles = rc.request().params().getAll("file").stream().collect(Collectors.toList());

            log.info("dataFiles {}", dataFiles.getClass().getName());

            //If no data files are specified, assume task data is being sent in request body.
            if(dataFiles == null || dataFiles.size() == 0){
                JsonArray tasks = rc.body().asJsonArray();
                rc.put("taskData", tasks);
            }else{
                //Otherwise, if data files were specified
                log.info("Loading data from {} files", dataFiles.size());

                rc.put("dataFiles", dataFiles);
                rc.put("processedFiles", new ArrayList<String>());
                rc.put("currentFile", dataFiles.remove(0));
            }

            rc.put("loadDataSetup", true);
        }


        rc.next();
    }

    private void loadDataFromFile(RoutingContext rc){
        String currentFile = rc.get("currentFile");
        if(currentFile != null && !currentFile.isBlank() && !currentFile.isEmpty()){

            /**
             * Execute in separate thread to avoid blocking main event loop.
             */
            vertx.executeBlocking(blocking->{
                    try{


                    log.info("Reading data from {} into memory", currentFile );
                    JsonArray tasks = new JsonArray(new String(Files.readAllBytes(Path.of(currentFile))));
                    //Set the task data for the current request
                    rc.put("taskData", tasks);

                    log.info("Adding {} to processed files list.", currentFile);
                    //Add the current file to the list of processed files.
                    ArrayList<String> processedFiles = rc.get("processedFiles");
                    processedFiles.add(currentFile);

                    //Update the current file to the next unprocessed file.
                    ArrayList<String> dataFiles = rc.get("dataFiles");
                    rc.put("currentFile", dataFiles.size() > 0?dataFiles.remove(0):null);

                    log.info("{} data files left.", dataFiles.size());
                    log.info("Next data file is {}", (String)rc.get("currentFile"));

                    rc.next();

                    }catch (IOException e){
                        log.error("Error reading data file @{}", currentFile);
                        log.error(e.getMessage(), e);
                        rc.response().setStatusCode(500).end();
                    }

                    blocking.complete();
                }).subscribe();

        }
    }

    private void buildTraces(RoutingContext rc){

        log.info("Hit build traces!");

        //Get the json traces from the routing context
        JsonArray tasks = rc.get("taskData");

        vertx.<List<Trace>>executeBlocking(blocking->{
            List<Trace> traces = tasks.stream()
                    .map(o->(JsonObject)o)
                    .map(Mind2WebUtils::processTask)
                    .collect(Collectors.toList());

            blocking.complete(traces);

        }).subscribe(traces->{

            log.info("Processed {} traces", traces.size());
            log.info("{} clicks", traces.stream().mapToLong(Trace::numClicks).sum());
            log.info("{} selects", traces.stream().mapToLong(Trace::numSelects).sum());
            log.info("{} types", traces.stream().mapToLong(Trace::numTypes).sum());
            log.info("{} total actions", traces.stream().mapToInt(Trace::size).sum());

//            log.info("XPaths:");
//            traces.stream().forEach(trace->trace.forEach(operation -> log.info("{}", operation.getTargetElementXpath())));

            log.info("Constructing nav model from traces!");

            vertx.executeBlocking(blocking->{
                traces.stream().forEach(this::buildNavModel);
                log.info("Trace construction complete. ");
                blocking.complete(traces);
            }).subscribe(_traces->{

                log.info("Saving traces to routing context.");
                rc.put("traces", traces);

                //Check to see if we have more files to process.
                String nextFile = rc.get("currentFile");
                if(nextFile != null){
                    rc.reroute(HttpMethod.POST, getFullModelConstructionRoutePath());
                }else{
                    log.info("Model construction complete");
                    neo4j.createNodeLabelsUsingWebsiteProperty();
                    rc.response().setStatusCode(200).end();
                }
            });
        });
    }



    public void buildNavModel(Trace trace){

        log.info("Inserting trace {} into nav model. ", trace.getAnnotationId());

        ListIterator<Operation> it = trace.listIterator();

        //Do one pass through the trace to create/update all required nodes.
        while (it.hasNext()){

            Operation op = it.next();

            if(op instanceof Click){
                neo4j.processClick((Click) op, trace.getWebsite());
            }

            if(op instanceof Type){
                neo4j.processType((Type) op, trace.getWebsite());
            }

            if(op instanceof SelectOption){
                neo4j.processSelectOption((SelectOption) op, trace.getWebsite());
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

                NavNode a = neo4j.resolveNavNode(curr, trace.getWebsite());
                NavNode b = neo4j.resolveNavNode(next, trace.getWebsite());

                neo4j.bind(a, b);
            }


        }else if(trace.size() == 2){

            NavNode a = neo4j.resolveNavNode(trace.get(0), trace.getWebsite());
            NavNode b = neo4j.resolveNavNode(trace.get(1), trace.getWebsite());

            neo4j.bind(a,b);

        }else{
            log.info("Trace size: {}", trace.size());
            throw new RuntimeException("Trace is too small to model!");
        }

    }

    private String getFullModelConstructionRoutePath(){
        return _config.getString("apiPathPrefix").substring(0, _config.getString("apiPathPrefix").length()-2) + MODEL_CONSTRUCTION_PATH;
    }
}
