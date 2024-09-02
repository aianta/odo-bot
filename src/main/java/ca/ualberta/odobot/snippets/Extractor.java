package ca.ualberta.odobot.snippets;


import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.logpreprocessor.LogPreprocessor;
import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import ca.ualberta.odobot.semanticflow.navmodel.NavPath;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.ext.web.RoutingContext;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;

public class Extractor extends HttpServiceVerticle {


    private static final Logger log = LoggerFactory.getLogger(Extractor.class);

    private static ElasticsearchService elasticsearchService;

    private static SqliteService sqliteService;

    public Completable onStart(){
        super.onStart();

        //Initialize Elasticsearch Service Proxy
        ServiceProxyBuilder elasticsearchServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS);
        elasticsearchService = elasticsearchServiceProxyBuilder.build(ElasticsearchService.class);

        //Initalize SQLite Service Proxy
        ServiceProxyBuilder sqliteServiceProxyBuilder = new ServiceProxyBuilder(vertx.getDelegate())
                .setAddress(SQLITE_SERVICE_ADDRESS);
        sqliteService = sqliteServiceProxyBuilder.build(SqliteService.class);

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





    private List<String> getSnippets(DynamicXPath xPath, JsonObject entity, int entityIndex){

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
            //Add the parent HTML to our list of snippets
            snippets.add(element.outerHtml());
            sqliteService.saveSnippet(element.outerHtml(), xPath.toString(), "parent", htmlString, entity.getString("flightID") + "#" + entityIndex );

            //Add individual children matching the dynamic tag as snippets as well.
            Elements children = element.children();
            children.forEach(child->{

                if(child.tagName().equals(xPath.getDynamicTag())){
                    snippets.add(child.outerHtml());
                    sqliteService.saveSnippet(child.outerHtml(), xPath.toString(), "child", htmlString, entity.getString("flightID") + "#" + entityIndex );
                }
            });

        });

        return snippets;
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

}
