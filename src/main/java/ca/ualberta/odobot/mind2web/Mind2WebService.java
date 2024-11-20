package ca.ualberta.odobot.mind2web;

import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.semanticflow.navmodel.Neo4JUtils;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.EndNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.NavNode;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.StartNode;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceBinder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.NodeIterator;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;
import static ca.ualberta.odobot.mind2web.DocumentTainter.KEEP_ID_TAINT;

public class Mind2WebService extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(Mind2WebService.class);

    static Neo4JUtils neo4j;

    Route modelConstructionRoute;

    Route stateAbstractionRoute;

    Route stateAbstractionRouteV2;

    Route stateAbstractionRouteV3;

    Route stateAbstractionRouteV4;

    Route stateAbstractionRouteV4Update;

    Route mineDynamicXpathsRoute;

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

    private static final String MINE_DYNAMIC_XPATHS_PATH = "/mineDXpaths";

    Map<UUID, List<Trajectory>> requestTrajectories = new HashMap<>();

    Map<UUID, Map<String, String>> requestTrajectoryXpaths = new HashMap<>();

    Map<UUID, Map<String,String>> requestAnnotatedBackendIds = new HashMap<>();

    /**
     * Stores <guidanceId, current step>
     */
    Map<UUID, Integer> guidanceRequests = new HashMap<>();

    /**
     * Stores <guidanceId, List<NavPaths>
     * @return
     */
    Map<UUID, List<NavPath>> guidancePathMap = new HashMap<>();

    /**
     * Stores <guidanceId, Transaction>
     * @return
     */
    Map<UUID, Transaction> guidanceTransactions = new HashMap<>();

    /**
     * Stores<guidanceId, validActionIds>
     * @return
     */
    Map<UUID, Collection<String>> guidanceValidActionIds = new HashMap<>();


    Set<DynamicXPath> dynamicXPaths = new HashSet<>();

    public static SqliteService sqliteService;


    public Completable onStart(){
        super.onStart();

        //Init SQLite Service
        sqliteService = SqliteService.create(vertx.getDelegate());
        new ServiceBinder(vertx.getDelegate())
                .setAddress(SQLITE_SERVICE_ADDRESS)
                .register(SqliteService.class, sqliteService);

        //Configure model construction route
        modelConstructionRoute = api.route().method(HttpMethod.POST).path(MODEL_CONSTRUCTION_PATH);
        modelConstructionRoute.handler(this::loadDataSetup); //Figure out what/how to load
        modelConstructionRoute.handler(this::loadDataFromFile); //Load
        modelConstructionRoute.handler(this::buildTraces); //Build traces from loaded data

        //Configure a route for mining dynamic xpaths, this should be invoked after model construction.
        mineDynamicXpathsRoute = api.route().method(HttpMethod.POST).path(MINE_DYNAMIC_XPATHS_PATH)
                .handler(this::loadDataSetup)
                .handler(this::loadDataFromFile)
                .handler(this::mineDynamicXpaths);


        //Configure guidance route
        api.route().method(HttpMethod.POST).path("/guidance").handler(this::handleGuidance);

        //Configure state abstraction route
        stateAbstractionRoute = api.route().method(HttpMethod.POST).path("/state-abstraction");
        stateAbstractionRoute.handler(this::processMind2WebCandidates);
        stateAbstractionRoute.handler(this::cleanHTML);
        stateAbstractionRoute.handler(this::identifyCandidates);
        stateAbstractionRoute.handler(this::prune);

        //Configure state abstraction route for re-rooting strategy
        stateAbstractionRouteV2 = api.route().method(HttpMethod.POST).path("/state-abstraction-v2");
        stateAbstractionRouteV2.handler(this::cleanHTML);
        stateAbstractionRouteV2.handler(this::reroot);

        //Configure state abstraction route for annotated strategy
        stateAbstractionRouteV3 = api.route().method(HttpMethod.POST).path("/state-abstraction-v3");
        stateAbstractionRouteV3.handler(this::processAnnotatedStateAbstractionRequest);
        stateAbstractionRouteV3.handler(this::cleanHTML);
        stateAbstractionRouteV3.handler(this::annotate);
        stateAbstractionRouteV3.handler(this::identifyCandidates);
        stateAbstractionRouteV3.handler(this::prune);

        //Configure state abstraction route for annotated strategy w/ guidance characteristcs
        stateAbstractionRouteV4 = api.route().method(HttpMethod.POST).path("/state-abstraction-v4");
        stateAbstractionRouteV4.handler(this::processGuidedStateAbstractionRequest);
        stateAbstractionRouteV4.handler(this::processAnnotatedStateAbstractionRequest);
        stateAbstractionRouteV4.handler(this::cleanHTML);
        stateAbstractionRouteV4.handler(this::annotate);
        stateAbstractionRouteV4.handler(this::identifyCandidates);
        stateAbstractionRouteV4.handler(this::prune);


        /** Configure guided state abstraction update route, this is used to prune trajectories that are no longer relevant.
         *
         */
        stateAbstractionRouteV4Update = api.route().method(HttpMethod.POST).path("/update-trajectories");
        stateAbstractionRouteV4Update.handler(this::processTrajectoryUpdate);


        neo4j = new Neo4JUtils("bolt://localhost:7687", "neo4j", "odobotdb");

        return Completable.complete();
    }

    private void processMind2WebCandidates(RoutingContext rc){
        if(rc.queryParam("candidates") != null && rc.queryParam("candidates").size() > 0){
            JsonArray candidates = new JsonArray(rc.queryParam("candidates").get(0));
            rc.put("candidates", candidates);
        }

        rc.next();
    }

    private void processTrajectoryUpdate(RoutingContext rc){
        JsonObject updateInfo = rc.body().asJsonObject();

        //Get the backend node id of the element that was selected.
        String backendNodeId = updateInfo.getString("backend_node_id");
        UUID guidanceId = UUID.fromString(updateInfo.getString("guidance_id"));

        log.info("previous action element was: {}", backendNodeId);

        //Find the trajectory list associated with this request.
        List<Trajectory> trajectories = requestTrajectories.get(guidanceId);

        if(trajectories != null){

            //Get the backend node ids that we annotated last time we created an abstracted state for this request.
            Map<String,String> annotatedBackendIds = requestAnnotatedBackendIds.get(guidanceId);

            /**Find the names of the trajectories associated with the selected backend node id.
             *
             * So basically, say that 'trajectory-1' and 'trajectory-2' annotated the element with backend_node_id: 8.
             * Then 'trajectory-3' annotated the element with backend_node_id: 10.
             *
             * If the selected backend node id was '8' then we would prune trajectory-3 and keep trajectory-1 & 2.
             *
             */
            Set<String> matchedTrajectoryNames = annotatedBackendIds.entrySet().stream()
                    .filter(entry->entry.getValue().equals(backendNodeId))
                    .map(entry->entry.getKey())
                    .collect(Collectors.toSet());

            if(matchedTrajectoryNames.size() > 0){
                log.info("The following trajectories matched the previous action! {}", matchedTrajectoryNames);
            }
            /**
             * Go through the trajectories associated with this request and prune the ones
             * whose annotations were not chosen.
             */
            Iterator<Trajectory> trajectoryIterator = trajectories.iterator();
            while (trajectoryIterator.hasNext()){
                Trajectory trajectory = trajectoryIterator.next();
                if(!matchedTrajectoryNames.contains(trajectory.getName())){
                    trajectoryIterator.remove();
                }
            }

            requestTrajectories.put(guidanceId, trajectories);

        }

        rc.response().setStatusCode(200).end();

    }

    private void processGuidedStateAbstractionRequest(RoutingContext rc){

        JsonObject request = rc.body().asJsonObject();

        rc.put("guidance", true);

        /**
         * This methods looks for a request id, if one exists, this request
         * is a follow-up request. Similar to handling guidance.
         *
         * If one does not exist, this method should use the trajectory info
         * to construct a set of navpaths for this request.
         *
         * Then xpaths should be harvested from those navpaths, and put into the routing context
         * from where the annotation method should source its annotations.
         *
         */

        if(request.containsKey("id")){

            UUID guidanceId = UUID.fromString(request.getString("id"));
            log.info("Request had guidance id: {}", guidanceId.toString());
            rc.put("guidanceId", guidanceId);

            if(guidanceRequests.get(guidanceId) == null){
                requestTrajectories.remove(guidanceId);
                requestTrajectoryXpaths.remove(guidanceId);
                requestAnnotatedBackendIds.remove(guidanceId);

                rc.put("trajectoryXpaths", new HashMap<>());
                rc.next();
                return;
            }

            //Fetch the trajectories associated with this request
            List<Trajectory> trajectories = requestTrajectories.get(guidanceId);

            //Resolve the trajectory action uids to xpaths
            Map<String,String> trajectoryXpaths = new HashMap<>();

            //Use an iterator here so that we can remove trajectories that are out of actions.
            Iterator<Trajectory> trajectoryIterator = trajectories.iterator();
            while(trajectoryIterator.hasNext()){
                Trajectory trajectory = trajectoryIterator.next();

                String actionId = trajectory.nextActionId();
                if(actionId != null){
                    String xpath = neo4j.getXpathFromActionId(trajectory.nextActionId());
                    trajectoryXpaths.put(trajectory.getName(), xpath);
                }else{
                    trajectoryIterator.remove();
                }
            }

            requestTrajectories.put(guidanceId, trajectories);

            //If there aren't any valid trajectories left, no need to bother with this logic again.
            if(trajectories.size() == 0){
                guidanceRequests.remove(guidanceId);

            }

            log.info("Trajectory Xpaths:");
            trajectoryXpaths.forEach((name, xpath)->log.info("{} -> {}", name, xpath));

            requestTrajectoryXpaths.put(guidanceId, trajectoryXpaths);

            rc.put("trajectoryXpaths", trajectoryXpaths);

        }else{

            //Create a guidance id for this request.
            UUID guidanceId = UUID.randomUUID();
            rc.put("guidanceId", guidanceId);
            log.info("Created new guidance id: {}", guidanceId.toString());

            JsonObject trajectoryInfo = request.getJsonObject("trajectory_info");

            JsonObject symbolMapping = trajectoryInfo.getJsonObject("symbolMapping");

            List<Trajectory> trajectories = new ArrayList<>();
            Map<String,Trajectory> trajectoryMap = new HashMap<>();

            //Create trajectory objects for all trajectories submitted with the request.
            symbolMapping.forEach(entry->{
                Trajectory trajectory = new Trajectory();
                trajectory.setName((String)entry.getValue());
                trajectory.setAnnotationId(entry.getKey());

                trajectories.add(trajectory);
                trajectoryMap.put(trajectory.getAnnotationId(), trajectory);
            });

            JsonObject _trajectories = trajectoryInfo.getJsonObject("trajectories");

            //Populate trajectory objects with provided lists of action_uids
            _trajectories.forEach(entry->{

                Trajectory correspondingTrajectory = trajectoryMap.get(entry.getKey());

                correspondingTrajectory.setActions(((JsonArray)entry.getValue()).stream().map(o->(String)o).collect(Collectors.toList()));

            });

            //Resolve the trajectory action uids to xpaths
            Map<String, String> trajectoryXpaths = new HashMap<>();

            trajectories.forEach(trajectory -> {
                String actionId = trajectory.nextActionId();
                if(actionId != null){
                    String xpath = neo4j.getXpathFromActionId(trajectory.nextActionId());

                    trajectoryXpaths.put(trajectory.getName(), xpath);
                }

            });

            log.info("Trajectory Xpaths:");
            trajectoryXpaths.forEach((name, xpath)->log.info("{} -> {}", name, xpath));

            requestTrajectories.put(guidanceId, trajectories);
            requestTrajectoryXpaths.put(guidanceId, trajectoryXpaths);

            rc.put("trajectoryXpaths", trajectoryXpaths);

        }

        rc.next();
    }

    private void processAnnotatedStateAbstractionRequest(RoutingContext rc){

        JsonObject requestData =  rc.body().asJsonObject();

        String website = requestData.getString("website");
        String rawHtml = requestData.getString("raw_html");



        rc.put("website", website);
        rc.put("rawHTMLString", rawHtml);

        if(requestData.containsKey("candidates")){
            JsonArray candidates = requestData.getJsonArray("candidates");
            rc.put("candidates", candidates);
        }


        if(rc.get("trajectoryInfo") == null){
            JsonObject trajectoryInfo = requestData.getJsonObject("trajectory_info");
            rc.put("trajectoryInfo", trajectoryInfo);
        }

        rc.next();
    }

    /**
     * Expects raw HTML in the post body.
     *
     * Cleans it by:
     *  * removing styles, scripts, svgs, CHARDATA
     *
     * @param rc
     */
    private void cleanHTML(RoutingContext rc){

        String rawHTMLString;
        if(rc.get("rawHTMLString") == null){
            rawHTMLString = rc.body().asString();
        }else{
            rawHTMLString = rc.get("rawHTMLString");
        }


        String cleanHTMLString = HTMLCleaningTools.clean(rawHTMLString);

        //Save raw and clean strings to the routing context
        rc.put("rawHTMLString", rawHTMLString);
        rc.put("cleanHTMLString", cleanHTMLString);

        //log.info("cleanedHTMLString: \n{}", cleanHTMLString);

        //Compute some cleaning metrics
        double sizeDelta = rawHTMLString.length() - cleanHTMLString.length();
        double compressionRatio = (double)cleanHTMLString.length()/(double)rawHTMLString.length();

        log.info("RawHTML size: {} - CleanHTML size: {} delta: {} compressionRatio: {}", rawHTMLString.length(), cleanHTMLString.length(), sizeDelta, compressionRatio);

        //Save the length of the cleaned html, this helps compute stats about how much pruning the pruning step actually did later.
        rc.put("cleanSize", cleanHTMLString.length());
        rc.put("rawSize", rawHTMLString.length());

        rc.next();
    }

    private void identifyCandidates(RoutingContext rc){
        String website;
        if(rc.get("website") != null){
            website = rc.get("website");
        }else{
            website = rc.queryParam("website").get(0);
        }

        //Retrieve xpaths from the navmodel for this website and save them to the routing context.
        List<String> xpaths = neo4j.getXpathsForWebsite(website);
        rc.put("xpaths", xpaths);

        Document document;
        if (rc.get("document") != null){
            //If we're given an annotated document work with that.
            document = rc.get("document");
        }else{
            //If not, then we parse the cleaned HTML into a Jsoup Document, so we can taint candidates on the basis of the xpaths and dxpaths that have been retrieved.
            document = Jsoup.parse((String)rc.get("cleanHTMLString"));
        }



        //Taint elements with the xpaths
        if((rc.get("candidates") != null && ((JsonArray)rc.get("candidates")).size() == 0) || rc.get("candidates") == null){
            //Only use xpaths if candidates size is zero, or candidates are undefined
            DocumentTainter.taint(document, (List<String>)rc.get("xpaths"));
        }


        //Taint candidate elements if they've been provided.
        if(rc.get("candidates") != null && ((JsonArray)rc.get("candidates")).size() > 0){
            DocumentTainter.taint(document, (JsonArray) rc.get("candidates"));
        }


        //Taint elements with the dynamic xpaths
        //DocumentTainter.taintWithDynamicXpaths(document, _dxpaths);

        //Print some tainting stats.
        int taintedElements = 0;
        int untaintedElements = 0;
        for(Element e: document.getAllElements()){
            if(e.attributes().hasKey(DocumentTainter.TAINT)){
                taintedElements++;
            }else{
                untaintedElements++;
            }
        }

        log.info("{} xpaths and {} dynamic xpaths were used to taint. Cleaned Document has {} elements, of which {} are tainted. Taint ratio: {}", ((List<String>)rc.get("xpaths")).size(), 0, taintedElements + untaintedElements, taintedElements, (double)taintedElements/(double)(taintedElements + untaintedElements));

        //Save the document to the routing context and proceed to pruning step.
        rc.put("document", document);
        rc.next();

        //Retrieve dynamic xpaths for this website and save them to the routing context.
//        sqliteService.loadDynamicXpaths(website).onSuccess(dxpaths->{
//
//            List<DynamicXPath> _dxpaths = dxpaths.stream()
//                    .map(o->(JsonObject)o)
//                    .map(DynamicXPath::fromJson)
//                    .collect(Collectors.toList());
//
//            rc.put("dxpaths",_dxpaths);
//
//
//        });


    }

    private void prune(RoutingContext rc){

        /**
         * At this stage the elements to keep have been tainted by the previous step.
         * Our job is simply to iterate through all elements in the document and remove them if they are not tainted.
         */
        Document taintedDocument = rc.get("document");


        NodeIterator<Element> it = new NodeIterator<>(taintedDocument.root(), Element.class);

        while (it.hasNext()){
            Element cursor = it.next();
            //If the element isn't tainted, remove it.
            if(!cursor.attributes().hasKey(DocumentTainter.TAINT)){
                it.remove();
            }
        }

        //Next do another pass through the document with what's left and remove the taint attributes.
        it = new NodeIterator<>(taintedDocument.root(), Element.class);
        while (it.hasNext()){
            Element cursor = it.next();
            cursor.attributes().remove(DocumentTainter.TAINT);

            if(cursor.attributes().hasKey("backend_node_id")){

                //Only keep the backend node id if it has been marked by a special taint.
                if(cursor.attributes().hasKey(KEEP_ID_TAINT)){
                    //Replace backend_node_id with id.
                    String idValue = cursor.attributes().get("backend_node_id");
                    cursor.attributes().remove("backend_node_id");

                    //If the element has a website defined id, add it as an attribute, and let id be the backend node id
                    if(cursor.attributes().hasKey("id")){
                        String nonBackendNodeIdValue = cursor.attributes().get("id");
                        cursor.attributes().remove("id");
                        cursor.attributes().add(nonBackendNodeIdValue, null);
                    }

                    cursor.attributes().add("id", idValue);

                    //Remove the keep id taint
                    cursor.attributes().remove(KEEP_ID_TAINT);
                }else{
                    cursor.attributes().remove("backend_node_id");
                }
            }

            //Clear the buckeye
            if(cursor.attributes().hasKey("data_pw_testid_buckeye")){
                cursor.attributes().remove("data_pw_testid_buckeye");
            }

            //If the type attribute of an element has a value that is just its tagname, remove it.
            if(cursor.attributes().hasKey("type") && cursor.attributes().get("type").toLowerCase().equals(cursor.tagName().toLowerCase())){
                cursor.attributes().remove("type");
            }

        }

        //log.info("Pruned Tree:\n{}", taintedDocument.html());

        //TODO: Annotation logic
        Document document = rc.get("document");

        String output = document.html();
        output = output.replaceAll("\n", "");
        output = output.replaceAll("(?<=>) +(?=<)", ""); //Get rid of extra spaces between tags.

        log.info("\nOriginal Raw Document size: {}\nOriginal Cleaned Document Size: {}\nFinal Document Size: {}", (int)rc.get("rawSize"),(int)rc.get("cleanSize"), output.length());

        if(rc.get("guidanceId") != null){
            rc.response().putHeader("guidance_id", ((UUID)rc.get("guidanceId")).toString());
        }

        rc.response().putHeader("Content-Type","text/html").setStatusCode(200).end(output);

    }

    private void annotate(RoutingContext rc){

        Document document = Jsoup.parse((String)rc.get("cleanHTMLString"));

        //TrajectoryXpaths will be null if this is an unguided request.
        //TODO: this is going to break
        if(rc.get("trajectoryXpaths") == null && (boolean)rc.get("guidance") == false){
            JsonObject trajectoryInfo = rc.get("trajectoryInfo");
            JsonObject symbolMapping = trajectoryInfo.getJsonObject("symbolMapping");
            JsonObject trajectories = trajectoryInfo.getJsonObject("trajectories");

            trajectories.forEach(entry->{
                String annotationId = entry.getKey();
                List<String> actionIds = ((JsonArray)entry.getValue()).stream().map(s->(String)s).collect(Collectors.toList());

                List<String> xpaths = neo4j.getXpathsForTrajectory(actionIds);

                String annotation = symbolMapping.getString(annotationId);

                DocumentAnnotator.annotate(document, xpaths, annotation);

            });
        }else{
            /** If this is a guided request, then 'trajectoryXpaths' will contain a map <symbol, xpath>
             *  which we should use to annotate the document.
             */

            UUID guidanceId = rc.get("guidanceId");
            Map<String,String> trajectoryXpaths = rc.get("trajectoryXpaths");
            Map<String, String> annotatedIds = new HashMap<>();

            trajectoryXpaths.forEach((annotation, xpath)->{
                String backendId = DocumentAnnotator.annotate(document, xpath, annotation);
                if(backendId != null){
                    annotatedIds.put(annotation, backendId);
                }

            });

            log.info("Annotated Ids:");
            annotatedIds.forEach((name, xpath)->log.info("{} -> {}", name, xpath));

            requestAnnotatedBackendIds.put(guidanceId, annotatedIds);
        }



        //For debugging
        //log.info("annotatedDocument:\n{}", document.html());

        rc.put("document", document);
        rc.next();
    }

    private void reroot(RoutingContext rc){

        String website;
        if(rc.get("website") != null){
            website = rc.get("website");
        }else{
            website = rc.queryParam("website").get(0);
        }

        List<String> xpaths = neo4j.getXpathsForWebsite(website);

        rc.put("xpaths", xpaths);

        sqliteService.loadDynamicXpaths(website).onSuccess(dxpaths->{
            List<DynamicXPath> _dxpaths = dxpaths.stream()
                    .map(o->(JsonObject)o)
                    .map(DynamicXPath::fromJson)
                    .collect(Collectors.toList());


            rc.put("dxpaths", _dxpaths);

            Document document = Jsoup.parse((String)rc.get("cleanHTMLString"));

            List<Element> targetElements = ElementHarvester.getElementsByXpaths(document, xpaths);

            if(rc.queryParam("includeDXpaths").size() > 0){
                targetElements.addAll(ElementHarvester.getElementsByDynamicXpaths(document, _dxpaths));
            }

            List<String> elementStrings = targetElements.stream().map(Element::html).collect(Collectors.toList());

            document.body().html(""); //Clear the body of the cleaned document.

            elementStrings.forEach(el->document.body().prepend(el));

            NodeIterator<Element> it = new NodeIterator<>(document.root(), Element.class);
            while (it.hasNext()){
                Element cursor = it.next();

                if(cursor.attributes().hasKey("backend_node_id")){
                    //Replace backend_node_id with id.
                    String idValue = cursor.attributes().get("backend_node_id");
                    cursor.attributes().remove("backend_node_id");

                    //If the element has a website defined id, add it as an attribute, and let id be the backend node id
                    if(cursor.attributes().hasKey("id")){
                        String nonBackendNodeIdValue = cursor.attributes().get("id");
                        cursor.attributes().remove("id");
                        cursor.attributes().add(nonBackendNodeIdValue, null);
                    }

                    cursor.attributes().add("id", idValue);
                }

                //Clear the buckeye
                if(cursor.attributes().hasKey("data_pw_testid_buckeye")){
                    cursor.attributes().remove("data_pw_testid_buckeye");
                }

                //If the type attribute of an element has a value that is just its tagname, remove it.
                if(cursor.attributes().hasKey("type") && cursor.attributes().get("type").toLowerCase().equals(cursor.tagName().toLowerCase())){
                    cursor.attributes().remove("type");
                }
            }

            String output = document.html();
            log.info("final result: {}", output);

            output = output.replaceAll("\n", "");

            rc.response().putHeader("Content-Type", "text/html").setStatusCode(200).end(output);
            log.info("\nOriginal Raw Document size: {}\nOriginal Cleaned Document Size: {}\nFinal Document Size: {}", (int)rc.get("rawSize"),(int)rc.get("cleanSize"), output.length());



        });

    }

    private void handleGuidance(RoutingContext rc){

        JsonObject request = rc.body().asJsonObject();

        //A request that contains a guidance id is a 'follow-up' request, asking for the relevant action ids for the subsequent step.
        if(request.containsKey("id")){
            UUID guidanceId = UUID.fromString(request.getString("id"));

            if(guidanceRequests.get(guidanceId) == null){
                rc.response().setStatusCode(400).end(new JsonObject().put("error", "no guidance request with id " + guidanceId.toString()).encode());
                return;
            }

            //Update the step for this guidance request.
            guidanceRequests.put(guidanceId, guidanceRequests.get(guidanceId) + 1);

            //Fetch the paths for this guidance request
            List<NavPath> paths =  guidancePathMap.get(guidanceId);

            //Fetch valid action ids for this guidance request
            Collection<String> validActionIds = guidanceValidActionIds.get(guidanceId);

            //Produce the actions for the current step
            JsonArray actionIds = paths.stream().map(path->path.getActionIds(validActionIds))
                    .collect(ArrayList::new, (list, ids)->list.addAll(ids), ArrayList::addAll)
                    .stream().distinct()// Filter out duplicates.
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            JsonObject response = new JsonObject()
                    .put("id", guidanceId)
                    .put("step", guidanceRequests.get(guidanceId))
                    .put("actions", actionIds);

            rc.response().setStatusCode(200)
                    .putHeader("Content-Type","application/json")
                    .end(response.encode());

            //If the actions list was empty we're done with this guidance request.
            if(actionIds.size() == 0){
                guidancePathMap.remove(guidanceId);
                guidanceRequests.remove(guidanceId);
                guidanceValidActionIds.remove(guidanceId);
                Transaction tx = guidanceTransactions.get(guidanceId);
                tx.close();
                guidanceTransactions.remove(guidanceId);
            }

        }else{
            //If there is no id, then this is a new guidance request.
            UUID guidanceId = UUID.randomUUID();

            rc.put("guidanceId",  guidanceId);

            /**
             * Construct a map containing entries of the form:
             *
             * <annotation_id, [action_id, action_id, ...]
             */
            Map<String, List<String>> relevantTraces =
                    request.stream()
                            .map(entry->{

                                //Parse the JsonArray into a list of strings.
                                List<String> actionIds = ((JsonArray) entry.getValue()).stream().map(o->(String)o).collect(Collectors.toList());

                                return Map.entry(entry.getKey(), actionIds);
                            })
                            .collect(HashMap::new, (map, entry)->map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
            rc.put("relevantTraces", relevantTraces);

            log.info("relevantTraces size: {}", relevantTraces.size());

            //Collect all annotationIds together
            List<String> annotationIds = relevantTraces.keySet().stream().toList();

            //Collect all actionIds together;
            List<String> validActionIds = relevantTraces.values().stream().collect(ArrayList::new, (all, list)->all.addAll(list), ArrayList::addAll);
            log.info("number of valid actions: {}", validActionIds.size());


            //Get start nodes
            List<StartNode> startNodes = neo4j.getStartNodes(annotationIds);
            log.info("number of start nodes: {}", startNodes.size());


            //Get end nodes
            List<EndNode> endNodes = neo4j.getEndNodes(annotationIds);
            log.info("number of end nodes: {}", endNodes.size());


            Transaction tx = LogPreprocessor.graphDB.db.beginTx();

            List<NavPath> paths = neo4j.getMind2WebPaths(tx, startNodes, endNodes);

            guidanceValidActionIds.put(guidanceId, validActionIds);
            guidancePathMap.put(guidanceId, paths);
            guidanceRequests.put(guidanceId, 0);
            guidanceTransactions.put(guidanceId, tx);

            JsonArray actionIds = paths.stream().map(path->path.getActionIds(validActionIds))
                    .collect(ArrayList::new, (list, ids)->list.addAll(ids), ArrayList::addAll)
                    .stream().distinct()// Filter out duplicates.
                    .collect(JsonArray::new, JsonArray::add,JsonArray::addAll);

            JsonObject response = new JsonObject()
                    .put("id", guidanceId.toString())
                    .put("step", guidanceRequests.get(guidanceId))
                    .put("actions", actionIds);

            rc.response().setStatusCode(200)
                    .putHeader("Content-Type","application/json")
                    .end(response.encode());

        }

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
                    .map(Mind2WebUtils::taskToTrace)
                    .collect(Collectors.toList());

            blocking.complete(traces);

        }).subscribe(traces->{

            log.info("Processed {} traces", traces.size());
            log.info("{} clicks", traces.stream().mapToLong(Trace::numClicks).sum());
            log.info("{} selects", traces.stream().mapToLong(Trace::numSelects).sum());
            log.info("{} types", traces.stream().mapToLong(Trace::numTypes).sum());
            log.info("{} total actions", traces.stream().mapToInt(Trace::size).sum());

            //For debugging
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
                    rc.response().setStatusCode(200).end(Mind2WebUtils.targetTags.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily());
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

            if(op instanceof Start){
                //EventId for startNodes feel more meaningful if they were populated with the trace annotationId. Maybe this is a bad idea...idk...
                neo4j.processStart(trace.getWebsite(), trace.getAnnotationId());
            }

            if(op instanceof End){
                //EventId for endNodes feel more meaningful if they were populated with the trace annotationId. Maybe this is a bad idea...idk...
                neo4j.processEnd(trace.getWebsite(), trace.getAnnotationId());
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

    private void mineDynamicXpaths(RoutingContext rc){

        log.info("Hit Mine Dynamic Xpaths!");

        //Get the json traces from the routing context
        JsonArray tasks = rc.get("taskData");

        //Assemble a list of futures the return sets of Dynamic Xpaths mined from mind2web tasks.
        List<Future<Set<DynamicXPath>>> futures = tasks.stream()
                .map(o->(JsonObject)o)
                .map(json->Mind2WebUtils.taskToDXpath(json))
                .collect(ArrayList::new, (list,o)->list.addAll(o), ArrayList::addAll);

        Future.all(futures)
                .onSuccess(
                        compositeFuture -> {
                            List<Set<DynamicXPath>> miningResults = compositeFuture.list();

                            dynamicXPaths.addAll(miningResults.stream().collect(HashSet::new, (set,o)->set.addAll(o), HashSet::addAll));

                        }
                ).onComplete(done->{
                    log.info("Extracted {} dynamic xpaths", dynamicXPaths.size());

                    String nextFile = rc.get("currentFile");
                    if(nextFile != null){
                        rc.reroute(HttpMethod.POST, getFullMineDynamicXpathRoutePath());
                    }else{
                        log.info("Dynamic Xpath Mining complete");

                        JsonArray results = dynamicXPaths.stream()
                                .map(DynamicXPath::toJson)
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                        rc.response().putHeader("Content-Type", "application/json").setStatusCode(200).end(results.encodePrettily());
                    }
                });

    }

    private String getFullModelConstructionRoutePath(){
        return _config.getString("apiPathPrefix").substring(0, _config.getString("apiPathPrefix").length()-2) + MODEL_CONSTRUCTION_PATH;
    }

    private String getFullMineDynamicXpathRoutePath(){
        return _config.getString("apiPathPrefix").substring(0, _config.getString("apiPathPrefix").length()-2) + MINE_DYNAMIC_XPATHS_PATH;
    }

}
