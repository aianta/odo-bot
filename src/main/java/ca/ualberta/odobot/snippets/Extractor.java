package ca.ualberta.odobot.snippets;


import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.logpreprocessor.Constants.*;

public class Extractor extends HttpServiceVerticle {


    private static final Logger log = LoggerFactory.getLogger(Extractor.class);

    private static ElasticsearchService elasticsearchService;

    private static SqliteService sqliteService;

    private static SnippetExtractorService snippetExtractorService;

    public Completable onStart(){
        super.onStart();

        //Initalize SQLite Service Proxy
        ServiceProxyBuilder sqliteServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(SQLITE_SERVICE_ADDRESS);
        sqliteService = sqliteServiceProxyBuilder.build(SqliteService.class);

        //Setup Snippet Extractor Service
        snippetExtractorService = SnippetExtractorService.create(vertx.getDelegate(), sqliteService);
        new ServiceBinder(vertx.getDelegate())
                .setAddress(SNIPPET_EXTRACTOR_SERVICE_ADDRESS)
                .register(SnippetExtractorService.class, snippetExtractorService);

        //Initialize Elasticsearch Service Proxy
        ServiceProxyBuilder elasticsearchServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .setOptions(new DeliveryOptions().setSendTimeout(3600000)); //1hr timeout
        elasticsearchService = elasticsearchServiceProxyBuilder.build(ElasticsearchService.class);


        api.route().method(HttpMethod.GET).path("/processSnippets").handler(this::getCollapsedNodes);
        api.route().method(HttpMethod.GET).path("/processSnippets").handler(this::configureExtractorService);
        api.route().method(HttpMethod.GET).path("/processSnippets").handler(this::getFlights);
        api.route().method(HttpMethod.GET).path("/processSnippets").handler(this::processFlights);

        api.route().method(HttpMethod.GET).path("/extractSnippets").handler(this::getCollapsedNodes); //Get Dynamic XPaths and instances from Collapsed nodes.
        //Doing a lot in this handler to minimize memory usage. Initially I tried to retrieve all the necessary data and save snippets in a downstream handler, but that resulted in OOM errors.
        api.route().method(HttpMethod.GET).path("/extractSnippets").handler(this::getDOMSnapshotsAndSaveSnippets); //Get the actual timeline entities backing those instances from elasticsearch and use the dynamic xpaths on the retrieved  entities to produce and save snippets.


        return Completable.complete();
    }

    @Override
    public String serviceName() {
        return "Snippet Extractor";
    }

    @Override
    public String configFilePath() {
        return "config/snippet-extraction.yaml";
    }

    /**
     * Queries the application model to retrieve all CollapsedClickNodes. Then from those nodes, extracts:
     * <ul>
     *     <li>Dynamic XPaths</li>
     *     <li>Instances associated with the dynamic XPaths</li>
     * </ul>
     * @param rc
     */
    public void getCollapsedNodes(RoutingContext rc){

        Transaction tx = LogPreprocessor.graphDB.db.beginTx();

        try(
                //TODO: In the future should probably support fetching all kinds of relevant collapsed nodes, not only collapsed click nodes.
                Result result = tx.execute("match (n) where n:CollapsedClickNode return n");
                ResourceIterator<Node> it = result.columnAs("n");
        ){
            Map<DynamicXPath, String[]> dynamicXPaths = new HashMap<>();
            while (it.hasNext()){
                Node curr = it.next();

                String[] instances = (String[])curr.getProperty("instances");

                DynamicXPath dynamicXPath = NavPath.nodeToDynamicXPath(curr);
                dynamicXPaths.put(dynamicXPath, instances);

                sqliteService.saveDynamicXpath(dynamicXPath.toJson(), dynamicXPath.toString(), (String)curr.getProperty("id") );

            }

            rc.put("dynamicXPaths", dynamicXPaths);
        }
        tx.close();
        rc.next();
    }

    public void getDOMSnapshotsAndSaveSnippets(RoutingContext rc){

        //Read query parameters
        String index = rc.request().getParam("index");

        String flightIdentifierField = "flightID.keyword";

        Map<DynamicXPath, String[]> dynamicXPaths = rc.get("dynamicXPaths");

        Map<DynamicXPath, List<JsonObject>> xpathsAndEntities = new HashMap<>();

        //Resolve entities corresponding to instances for each dynamic xpath.
        Future f = null;

        Iterator<Map.Entry<DynamicXPath, String[]>> it = dynamicXPaths.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<DynamicXPath, String[]> curr = it.next();
            DynamicXPath xpath = curr.getKey();
            String[] instances = curr.getValue();

            if(f == null){
                f = getEntitiesAndSaveSnippets(xpath, instances, index, flightIdentifierField);
            }else{
                f.compose(result->getEntitiesAndSaveSnippets(xpath, instances, index, flightIdentifierField));
            }
        }

        f.onSuccess(done->log.info("Saved All Snippets"));


        rc.response().setStatusCode(200).end();

    }

    private Future<Void> getEntitiesAndSaveSnippets (DynamicXPath xpath, String[] instances, String index, String flightIdentifierField){

        Map<String, Set<Integer>> flightEntityIndexMap = getFlightEntityIndexMap(instances);

        Set<String> flightIds = flightEntityIndexMap.keySet();

        log.info("{} flights to retrieve for dynamicXPath: {}{}{}", flightIds.size(), xpath.getPrefix(),xpath.getDynamicTag(), xpath.getSuffix());

        Future f = null;

        for(String flightId: flightIds){
            if (f == null){
                f = elasticsearchService.fetchFlightEvents(index, flightId, flightIdentifierField, new JsonArray())
                        .compose(flightData->handleFlightData(flightData, flightId, flightEntityIndexMap, xpath));
            }else{
                f.compose(last->elasticsearchService.fetchFlightEvents(index, flightId, flightIdentifierField, new JsonArray())
                        .compose(flightData->handleFlightData(flightData, flightId, flightEntityIndexMap, xpath)));
            }
        }

        f.onFailure(err->log.error("Error getting entities and saving snippets!" ));
        f.onSuccess(done->log.info("Done processing entities and saving snippets"));

        return Future.succeededFuture();
    }

    private Future<Void> handleFlightData(List<JsonObject> flightData, String flightId, Map<String, Set<Integer>> flightEntityIndexMap, DynamicXPath xpath){


        if(flightData.size() == 0){
            log.error("Flight {} returned with 0 events!", flightId);
            throw new RuntimeException("Flight "+flightId+" returned with 0 events!");
        }

        //We don't need to iterate through everything, because we have the exact indices of entities we are interested in.
        Set<Integer> entityIndices = flightEntityIndexMap.get(flightId);

        entityIndices.forEach(i->{
            log.info("Retrieved data for instance {}#{}", flightId, i);

            //Save the snippets to the filesystem.
            ListIterator<String> iterator = getSnippets(xpath, flightData.get(i), i).listIterator();
            while (iterator.hasNext()){
                String snippet = iterator.next();

                vertx.fileSystem()
                        .writeFile(_config.getString("snippetOutputDir") + UUID.randomUUID().toString() + ".html", Buffer.buffer(snippet))
                        .subscribe()
                ;
            }

        });

        return Future.succeededFuture();
    }


    /**
     * Apply a dynamic xpath to a timeline entity containing a DOMSnapshot to extract snippets.
     * @param xPath the dynamic xpath to use when extracting snippets
     * @param entity the JSON object representation of the timeline entity from which to extract snippets.
     * @param entityIndex the index of that entity in its parent timeline
     * @return a list of snippets.
     */
    public static List<String> getSnippets(DynamicXPath xPath, JsonObject entity, int entityIndex){

        if(!entity.containsKey("eventDetails_domSnapshot")){
            log.error("Entity does not contain DOMSnapshot, cannot extract snippets");
            return List.of();
        }

        List<String> snippets = new ArrayList<>();

        JsonObject _DOMSnapshotJson = new JsonObject(entity.getString("eventDetails_domSnapshot"));
        String htmlString = _DOMSnapshotJson.getString("outerHTML");

        Document document = Jsoup.parse(htmlString);

        log.info("Attempting to extract snippet with dynamic tag:\n{}", xPath.toJson().encodePrettily());

        Elements parentElements = document.selectXpath(xPath.getPrefix()); //This should yield the parent element.
        log.info("Found {} parent elements matching dynamic xpath prefix.", parentElements.size());

        parentElements.forEach(element -> {

            //Add individual children matching the dynamic tag as snippets as well.
            Elements children = element.children();
            int childSnippetCount = 0;
            for(Element child: children){
                if(child.tagName().equals(xPath.getDynamicTag()) && matchesSuffix(child, xPath)){
                    snippets.add(child.outerHtml());
                    sqliteService.saveSnippet(child.outerHtml(), xPath.toString(), "child", htmlString, entity.getString("flightID") + "#" + entityIndex );
                    childSnippetCount++;
                }
            }
            //Save the parent element if we found multiple children matching the dynamic xpath.
            if(childSnippetCount > 1){
                snippets.add(element.outerHtml());
                sqliteService.saveSnippet(element.outerHtml(), xPath.toString(), "parent", htmlString, entity.getString("flightID") + "#" + entityIndex);
            }

        });

        return snippets;
    }

    /**
     * Apply a dynamic xpath to a timeline entity containing a DOMSnapshot to extract snippets.
     *
     * Optimized for bulk processing. Waits for SQLite saves to complete before returning the future. Timeline ID is used as instance source.
     *
     * @param xPath the dynamic xpath to use when extracting snippets
     * @param entity the JSON object representation of the timeline entity from which to extract snippets.

     * @return a list of snippets.
     */
    public static Future<List<String>> getSnippets(DynamicXPath xPath, JsonObject entity){

        if(!entity.containsKey("eventDetails_domSnapshot")){
            //log.error("Entity does not contain DOMSnapshot, cannot extract snippets");
            return Future.succeededFuture(List.of());
        }

        List<String> snippets = new ArrayList<>();

        JsonObject _DOMSnapshotJson = new JsonObject(entity.getString("eventDetails_domSnapshot"));
        String htmlString = _DOMSnapshotJson.getString("outerHTML");

        Document document = Jsoup.parse(htmlString);

        //log.info("Attempting to extract snippet with dynamic tag:\n{}", xPath.toJson().encodePrettily());

        Elements parentElements = document.selectXpath(xPath.getPrefix()); //This should yield the parent element.
        //log.info("Found {} parent elements matching dynamic xpath prefix.", parentElements.size());

        List<Future> sqliteSaveFutures = new ArrayList<>();

        parentElements.forEach(element -> {

            //Add individual children matching the dynamic tag as snippets as well.
            Elements children = element.children();
            int childSnippetCount = 0;



            for(Element child: children){
                if(child.tagName().equals(xPath.getDynamicTag()) && matchesSuffix(child, xPath)){
                    snippets.add(child.outerHtml());
                    sqliteSaveFutures.add(sqliteService.saveSnippet(child.outerHtml(), xPath.toString(), "child", htmlString, entity.getString("flightID") ));
                    childSnippetCount++;
                }
            }
            //Save the parent element if we found multiple children matching the dynamic xpath.
            if(childSnippetCount > 1){
                snippets.add(element.outerHtml());
                sqliteSaveFutures.add(sqliteService.saveSnippet(element.outerHtml(), xPath.toString(), "parent", htmlString, entity.getString("flightID")));
            }

        });


        return CompositeFuture.all(sqliteSaveFutures).compose(done->Future.succeededFuture(snippets));
    }

    public static boolean matchesSuffix(Element element, DynamicXPath xPath){

        //Create a test xpath, with the current element selected for the dynamic tag, and the suffix of dynamic xpath.

        String testXpath = xPath.getPrefix() + "/" + element.tagName();
        int elementIndex = element.elementSiblingIndex();


        testXpath += "[" + elementIndex + "]";


        testXpath += xPath.getSuffix();

        Elements result = element.selectXpath(testXpath);

        return  result.size() > 0;

    }

    private Map<String, Set<Integer>> getFlightEntityIndexMap(String [] instances){

        Map<String, Set<Integer>> result = new HashMap<>();

        Arrays.stream(instances).forEach(instance->{
            //An instance comes in the form '<timeline-uuid>#<index-of-entity>
            String flightId = instance.split("#")[0];

            Set<Integer> indices = result.getOrDefault(flightId, new HashSet<>());
            indices.add(Integer.parseInt(instance.split("#")[1]));

            result.put(flightId, indices);
        });

        return result;
    }


    private void configureExtractorService(RoutingContext rc){

        Map<DynamicXPath, String[]> dynamicXPaths = rc.get("dynamicXPaths");
        log.info("Configuring Snippet Extractor Service!");
        snippetExtractorService.setDynamicXPaths(
                dynamicXPaths.keySet().stream().map(DynamicXPath::toJson).collect(Collectors.toList()))
                .onSuccess(done->rc.next());

    }


    private void getFlights(RoutingContext rc){

        //Read query parameters
        String index = rc.request().getParam("index");

        elasticsearchService.getFlights(index, "flightID.keyword")
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(flightSet->{

                    List<String> toDo = new ArrayList<>();

                    toDo.addAll(flightSet);

                    rc.put("todo", toDo);

                    rc.next();
                });

    }

    private void processFlights(RoutingContext rc){

        //Read query parameters
        String index = rc.request().getParam("index");

        if(rc.get("todo") != null){
            List<String> flights = rc.get("todo");



            Future f = null;
            while (flights.size() > 0){
                log.info("{} flights to process!", flights.size());
                String flight = flights.remove(0);
                log.info("Starting snippet extraction for flight {} in index {} ", flight, index);

                if(f == null){
                    f = elasticsearchService.findSnippetsInFlight(index, flight, "flightID.keyword", new JsonArray())
                            .onSuccess(done->log.info("Done flight {} in index {}", flight, index));
                }else{
                    f = f.compose(previousDone->{
                        log.info("Starting next flight");
                        return elasticsearchService.findSnippetsInFlight(index, flight, "flightID.keyword", new JsonArray())
                                .onSuccess(done->log.info("Done flight {} in index {}", flight, index));
                    });
                }

            }

            log.info("Queued up all processing.");

            f.onFailure(err->{
                log.error("Error while extracting snippets!");
                log.error(((Throwable)err).getMessage(), (Throwable) err);
                    })
                    .onSuccess(done->log.info("All flights processed!"));


            rc.response().setStatusCode(200).end();
        }

    }
}
