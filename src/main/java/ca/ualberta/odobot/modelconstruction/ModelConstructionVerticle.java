package ca.ualberta.odobot.modelconstruction;

import ca.ualberta.odobot.common.Predicates;
import ca.ualberta.odobot.common.RobulaPlus;
import ca.ualberta.odobot.common.Utils;
import ca.ualberta.odobot.modelconstruction.impl.TagAndAttributeStrategy;
import ca.ualberta.odobot.common.HttpServiceVerticle;
import ca.ualberta.odobot.elasticsearch.ElasticsearchService;
import ca.ualberta.odobot.modelconstruction.impl.visitors.LabelingNodeVisitor;
import ca.ualberta.odobot.modelconstruction.statelabeling.StateLabelingService;
import ca.ualberta.odobot.sqlite.SqliteService;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import io.vertx.rxjava3.ext.web.RoutingContext;

import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.common.Predicates.stateClusteringEventFilter;
import static ca.ualberta.odobot.logpreprocessor.Constants.ELASTICSEARCH_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;
import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

/**
 * Exposes HTML cleaning functionality.
 */
public class ModelConstructionVerticle extends HttpServiceVerticle {

    private static final Logger log = LoggerFactory.getLogger(ModelConstructionVerticle.class);

    @Override
    public String serviceName() {
        return "ModelConstructionService";
    }

    @Override
    public String configFilePath() {
        return "config/model-construction.yaml";
    }

    public static SqliteService sqliteService;
    public static ElasticsearchService elasticsearchService;
    public static StateLabelingService stateLabelingService;

    private static String ODO_LSH_HOST = "172.29.71.50";
    private int ODO_LSH_PORT = 5000;

    private WebClient webClient;

    private CleanerService cleanerService;
    private RobulaPlus robulaPlus = new RobulaPlus();

    @Override
    public Completable onStart() {
        super.onStart().subscribe();

        //Override odo LSH host from config file.
        ODO_LSH_HOST = _config.getString("odoLshHost");
        ODO_LSH_PORT = Integer.parseInt(_config.getString("odoLshPort"));



        WebClientOptions webClientOptions = new WebClientOptions()
                .setUserAgent("OdoBot");
        webClient = WebClient.create(vertx.getDelegate(), webClientOptions);

        //Init SQLite Service Proxy
        sqliteService = SqliteService.createProxy(vertx.getDelegate(), SQLITE_SERVICE_ADDRESS);

        //Init ElasticSearch Service Proxy
        elasticsearchService =  new ServiceProxyBuilder(vertx.getDelegate())
                .setOptions(new DeliveryOptions().setSendTimeout(300000))
                .setAddress(ELASTICSEARCH_SERVICE_ADDRESS)
                .build(ElasticsearchService.class);

        cleanerService = CleanerService.create(vertx.getDelegate(), _config, new TagAndAttributeStrategy(vertx.getDelegate()));

        stateLabelingService = StateLabelingService.create(vertx.getDelegate(), _config);


        api.route().method(HttpMethod.GET).path("/sampleDOMSnapshot").handler(this::sampleDOMSnapshot);
        api.route().method(HttpMethod.POST).path("/clean").handler(this::clean);
        api.route().method(HttpMethod.POST).path("/nodeLinks").handler(this::nodeLinks);
        api.route().method(HttpMethod.GET).path("/buildStateClusters").handler(this::buildStateClusters);
        api.route().method(HttpMethod.GET).path("/mineCommonSubstructures").handler(this::mineCommonDOMSubstructures);
        api.route().method(HttpMethod.GET).path("/computeAnnotations").handler(this::computeAnnotations);
        api.route().method(HttpMethod.GET).path("/loadDOMSnapshots").handler(this::loadDOMSnapshots);
        api.route().method(HttpMethod.GET).path("/resolveClusteredNodes")
                .handler(this::mineCommonDOMSubstructures)
                .handler(this::getNodeClustering)
                .handler(this::getNodeClusteringDocument)
                .handler(this::resolveClusterNodesV2)
        ;
        api.route().method(HttpMethod.GET).path("/labelStateClusters")
                .handler(this::buildStateClusters)
                .handler(this::getClustering)
                .handler(this::getClusterSnapshots);

        return Completable.complete();

    }


    private void computeAnnotations(RoutingContext rc) {
        String sourceIndex = rc.request().getParam("srcIndex");
        int numPerm = rc.queryParam("numPerm").isEmpty()?512:Integer.parseInt(rc.queryParam("numPerm").get(0));

        elasticsearchService.fetchAll(sourceIndex)
                .compose(events->
                        //Filter out everything but click events
                        Future.succeededFuture(events.stream().filter(Predicates.annotationEventFilter()).toList()))
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(clickEvents->{
                        JsonArray results = new JsonArray();
                        Iterator<JsonObject> it = clickEvents.iterator();
                        Future<JsonObject> f = null;
                        while (it.hasNext()) {
                            JsonObject clickEvent = it.next();

                            JsonObject domSnapshotData = new JsonObject(clickEvent.getString("eventDetails_domSnapshot"));
                            String snapshotHTML = domSnapshotData.getString("outerHTML");
                            String targetElementXpath = clickEvent.getString("eventDetails_xpath");
                            String baseURI = clickEvent.containsKey("eventDetails_element")?new JsonObject(clickEvent.getString("eventDetails_element")).getString("baseURI"):null;

                            if (f == null){
                                f = cleanerService.toElementAnnotationQuery(snapshotHTML, targetElementXpath).compose(request->{
                                    request.put("baseURI", baseURI);
                                    return Future.succeededFuture(request);
                                }).compose(request->{
                                    return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/query")
                                            .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                            .sendJson(request)
                                            .onFailure(err->log.error(err.getMessage(), err))
                                            .compose(response->Future.succeededFuture(response.bodyAsJsonObject()));
                                }).compose(result->{
                                    results.add(result);
                                    return Future.succeededFuture(result);
                                });
                            }else{
                                f = f.compose(done->{
                                    return cleanerService.toElementAnnotationQuery(snapshotHTML, targetElementXpath).compose(request->{
                                        request.put("baseURI", baseURI);
                                        return Future.succeededFuture(request);
                                    }).compose(request->{
                                        return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/query")
                                                .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                                .sendJson(request)
                                                .onFailure(err->log.error(err.getMessage(), err))
                                                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()));
                                    }).compose(result->{
                                        results.add(result);
                                        return Future.succeededFuture(result);
                                    });
                                });
                            }
                        }

                        f.onSuccess(done->{
                            rc.response().setStatusCode(200).end(results.encodePrettily());
                        })
                                .onFailure(err->log.error(err.getMessage(), err));

//                        Future.all(clickEvents.stream()
//                                .map(clickEvent->{
//
//                                    //Process the DOMSnapshot into node-links format
//                                    //Identify the target element
//                                    //Send everything to OdoLSH for querying
//                                    JsonObject domSnapshotData = new JsonObject(clickEvent.getString("eventDetails_domSnapshot"));
//                                    String snapshotHTML = domSnapshotData.getString("outerHTML");
//                                    String targetElementXpath = clickEvent.getString("eventDetails_xpath");
//                                    String baseURI = clickEvent.containsKey("eventDetails_element")?new JsonObject(clickEvent.getString("eventDetails_element")).getString("baseURI"):null;
//
//                                    return cleanerService.toElementAnnotationQuery(snapshotHTML, targetElementXpath)
//                                            .compose(request->{
//                                                request.put("baseURI", baseURI );
//                                                return Future.succeededFuture(request);
//                                            });
//
//                                })
//                                .toList())
//                                .compose(annotationRequests->{
//
//
//
//                                    return Future.all(
//                                    annotationRequests.list().stream()
//                                            .map(o->(JsonObject)o)
//                                            .map(request->{
//
//                                                return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/query")
//                                                        .addQueryParam("minhash_perm", Integer.toString(numPerm))
//                                                        .sendJson(request)
//                                                        .onFailure(err->log.error(err.getMessage(), err))
//                                                        .compose(response->Future.succeededFuture(response.bodyAsJsonObject()));
//
//                                            }).toList());
//
//                                })
//                                .onFailure(err->log.error(err.getMessage(), err))
//                                .onSuccess(annotations->{
//                                    JsonArray result = annotations.list().stream().map(o->(JsonObject)o).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
//
//
//                                })
//                        ;




                        }
                )

        ;

    }



    private void loadDOMSnapshots(RoutingContext rc){
        String sourceIndex = rc.queryParam("srcIndex").get(0);

        String eventBusAddress = UUID.randomUUID().toString();
        MessageConsumer messageConsumer = vertx.eventBus().consumer(eventBusAddress, msg->{
            JsonObject event = (JsonObject) msg.body();
            if(stateClusteringEventFilter().test(event)){

                JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                catalogHTMLAttributes(domSnapshotData.getString("outerHTML"))
                        .compose(done->cleanerService.cleanHTML(domSnapshotData.getString("outerHTML")))

                        .compose(cleanedHTML->{
                            return sqliteService.saveDOMSnapshot(
                                    event.getString("mongo_id"),
                                    cleanedHTML,
                                    baseURI,
                                    sourceIndex

                            );

                        })
                        .onSuccess(done->log.info("Saved {} to Sqlite", event.getString("mongo_id")))
                        .onFailure(err->log.error("Error saving DOMSnapshot to SQLite. " + err.getMessage(), err ));;
            }
        });

        elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                .onFailure(err->log.error(err.getMessage(), err))
                .onSuccess(done->{
                    messageConsumer.getDelegate().unregister();
                    rc.getDelegate().response().setStatusCode(200).end();
                });
    }

    //Save all the HTML attributes from an HTML document to sqlite
    private Future<Void> catalogHTMLAttributes(String html){
        Document document = Jsoup.parse(html);
        List<Future<Void>> attributeFutures = new ArrayList<>();

         class AttributeHarvester implements NodeVisitor{
             public List<JsonArray> attributeData = new ArrayList<>();
             @Override
             public void head(Node node, int i) {
                 if(node instanceof Element){
                     Element element = (Element) node;
                     element.attributes().forEach(attribute -> {
                         attributeData.add(List.of(element.tagName(),attribute.getKey(), attribute.getValue() ).stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll) );
                     });

                 }
             }
         }

        AttributeHarvester attributeHarvester = new AttributeHarvester();
        document.traverse(attributeHarvester);


        return sqliteService.saveHTMLAttributes(attributeHarvester.attributeData);
    }


    private void getClusterSnapshots(RoutingContext rc){
        log.info("Fetching relevant snapshots from sqlite...");
        Map<String, Set<String>> clusterMap = rc.get("clusterMap");

        try{
            List<JsonObject> results = new ArrayList<>();

            //Define a function that given a cluster Id and a set of document ids, retrieves the HTML for those documents from sqlite and produces a label for the cluster.
            BiFunction<String, Set<String>, Future<JsonObject>> func = (clusterId, documentIds)->{
                return sqliteService.getDomSnapshots(documentIds)
                        .compose(snapshots->stateLabelingService.generateStateLabeling(clusterId, snapshots))
                        .onFailure(err->log.error(err.getMessage(), err));
            };

            clusterMap.forEach((key, value) -> log.info("{} - {}", key, value));

            Iterator<Map.Entry<String, Set<String>>> clusterIterator = clusterMap.entrySet().iterator();
            Future<JsonObject> f = null;

            while (clusterIterator.hasNext()){
                Map.Entry<String, Set<String>> entry = clusterIterator.next();
                log.info("{} - {}", entry.getKey(), entry.getValue());
                if (f == null){
                    f = func.apply(entry.getKey(), entry.getValue()).compose(result->{
                        results.add(result);
                        return Future.succeededFuture();
                    });
                }else{
                    f = f.compose(done->func.apply(entry.getKey(), entry.getValue()).compose(result->{
                        results.add(result);
                        return Future.succeededFuture();
                    }));
                }

            }

            f.onFailure(err->log.error(err.getMessage(), err))
                    .onSuccess(done->{
                        log.info("Done creating cluster labels.");
                        rc.getDelegate().response().setStatusCode(200).end(results.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll).encodePrettily());
                    });

        }catch (Exception e){
            log.error(e.getMessage(),e);
        }




    }

    //Only include clusters who's items share a parent element.
    private void resolveClusterNodesV2(RoutingContext rc){
        JsonObject result = new JsonObject();

        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");
        Document document = rc.get("document");

        Iterator<Map.Entry<String, List<JsonObject>>> it =  clusterMap.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String, List<JsonObject>> cluster = it.next();

            Set<Element> parents = new HashSet<>();

            //Filter out cluster items that don't have a robustXpath (IE: text nodes, #root, etc)
            JsonArray processedClusterItems = cluster.getValue().stream().filter(entry->entry.containsKey("robustXpath"))
                    .map(item->{
                        String robustXpath = item.getString("robustXpath");
                        Element element = document.selectXpath(robustXpath).get(0);
                        if(element.hasParent()){
                            parents.add(element.parent());
                        }
                        return element.html();
                    })
                    .filter(html->!html.isEmpty())
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            if (!processedClusterItems.isEmpty() && parents.size() == 1){
                //Only interested in clusters who share the same parent element
                result.put(cluster.getKey(), parents.iterator().next().html());
            }
        }

        rc.getDelegate().response().setStatusCode(200).end(result.encodePrettily());

    }

    private void resolveClusterNodes(RoutingContext rc){
        JsonObject result = new JsonObject();

        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");
        Document document = rc.get("document");

        Iterator<Map.Entry<String, List<JsonObject>>> it =  clusterMap.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String, List<JsonObject>> cluster = it.next();

            //Filter out cluster items that don't have a robustXpath (IE: text nodes, #root, etc)
            JsonArray processedClusterItems = cluster.getValue().stream().filter(entry->entry.containsKey("robustXpath"))
                    .map(item->{
                        String robustXpath = item.getString("robustXpath");
                        Element element = document.selectXpath(robustXpath).get(0);
                        return element.html();
                    })
                    .filter(html->!html.isEmpty())
                    .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

            if (!processedClusterItems.isEmpty()){
                result.put(cluster.getKey(), processedClusterItems);
            }
        }

        rc.getDelegate().response().setStatusCode(200).end(result.encodePrettily());

    }


    private void getNodeClusteringDocument(RoutingContext rc){
        Map<String, List<JsonObject>> clusterMap = rc.get("clusterMap");

        if(clusterMap != null && clusterMap.size() > 0){
            Optional<Map.Entry<String, List<JsonObject>>> itemOptional =  clusterMap.entrySet().stream().filter(entry->entry.getValue().size()>0).findFirst();
            if(itemOptional.isPresent()){
                JsonObject clusterItem = itemOptional.get().getValue().get(0);
                String documentId = clusterItem.getString("id").split("_")[0];
                sqliteService.getDomSnapshots(Set.of(documentId))
                        .compose(docs->Future.succeededFuture(docs.get(0)))
                        .onSuccess(domSnapshot->{

                            String html = domSnapshot.getString("snapshot");
                            Document document = Jsoup.parse(html);
                            rc.put("document", document);
                            rc.put("snapshot", domSnapshot);
                            rc.next();
                        });
            }
        }

    }

    private void getNodeClustering(RoutingContext rc){
        String lshId = rc.get("lshId").toString();

        log.info("Fetching node clustering from LSH: {}",lshId);
        webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId)).send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onSuccess(response->{
                    JsonObject clusters = response.getJsonObject("clusters");
                    Map<String, List<JsonObject>> clusterMap = new HashMap<>();
                    clusters.forEach(entry->{
                        if(!entry.getKey().equals("-1")){
                            clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                    .map(o->(JsonObject)o)
                                    .collect(Collectors.toList())
                            );
                        }
                    });

                    rc.put("clusterMap", clusterMap);
                    rc.next();
                })
        ;
    }

    private void getClustering(RoutingContext rc){
        String lshId = rc.get("lshId").toString();

        log.info("Fetching clustering information from LSH: {}", lshId);
        webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId)).send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onSuccess(response->{

                        JsonObject clusters = response.getJsonObject("clusters");
                        Map<String, Set<String>> clusterMap = new HashMap<>();
                        clusters.forEach(entry->{
                            if(!entry.getKey().equals("-1")){ //Ignore the noise cluster produced by DBSCAN
                                /**
                                 * Cluster map contains <clusterId > : [list of document ids in the cluster..]
                                 * "1" -> ["696aa802acc7a9e8a7a4a4c0", "696aa802acc7a9e8a7a4a4cc", ...]
                                 */
                                clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                        .map(o->(JsonObject)o)
                                        .map(o->o.getString("id"))
                                        .collect(Collectors.toSet())
                                );
                            }
                        });

                    rc.put("clusterMap", clusterMap);
                    rc.next();


                })
        ;
    }

    private Future<String> makeMinhashLSHForSnapshotNodes(String snapshotId, String baseURI, String html, double threshold, int numPerm){

        Promise<String> promise = Promise.promise();


        webClient
                .post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH")
                .sendJson(new JsonObject()
                        .put("threshold", threshold)
                        .put("num_perm", numPerm)
                ).compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(response->{
                    String lshId = response.getString("id");

                    log.info("Created LSH model {} for DOMSnapshot {}", lshId, snapshotId );

                    cleanerService.toNodeLinks(html)
                            .compose(nodeLinks->{
                                nodeLinks.put("id", snapshotId);
                                nodeLinks.put("baseURI", baseURI);

                                return webClient.put(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId)
                                        .addQueryParam("minhash_perm", Integer.toString(numPerm))
                                        .sendJson(new JsonArray().add(nodeLinks));
                            })
                            .onFailure(err->{
                                log.error(err.getMessage(),err);
                                promise.fail(err);
                            })
                            .onSuccess(done->{
                                promise.complete(lshId);
                            })
                    ;

                });

        return promise.future();

    }

    /**
     * like {@link #buildStateClusters(RoutingContext)} but pulls all documents into memory first. This allows a more controlled insertion into odo-LSH
     * @param rc
     */
    private void mineCommonDOMSubstructures(RoutingContext rc){
        String sourceIndex = rc.queryParam("srcIndex").get(0);

        //Minhash LSH parameters
        double threshold = rc.queryParam("threshold").isEmpty()?0.5:Double.parseDouble(rc.queryParam("threshold").get(0));
        int numPerm = rc.queryParam("numPerm").isEmpty()?256:Integer.parseInt(rc.queryParam("numPerm").get(0));

        //DBSCAN parameters
        double eps = rc.queryParam("eps").isEmpty()?0.9:Double.parseDouble(rc.queryParam("eps").get(0));
        int minSamples = rc.queryParam("minSamples").isEmpty()?2:Integer.parseInt(rc.queryParam("minSamples").get(0));

        String clusteringId = rc.queryParam("clusteringId").isEmpty()?UUID.randomUUID().toString():rc.queryParam("clusteringId").get(0);
        List<Future<?>> todo = List.of();
        if(!rc.queryParam("clusteringId").isEmpty()){
            //If the clustering id is prespecified, we don't need to save the clustering info to the database as it will already exist.
            todo = List.of(
                    sqliteService.getMinedSnapshotIds(clusteringId),
                    elasticsearchService.fetchAll(sourceIndex)
            );
        }else{
            todo = List.of(
                    sqliteService.getMinedSnapshotIds(clusteringId), //Get any existing progress towards mining common sub-structures for this clustering. Facilitates resuming interrupted mining.
                    elasticsearchService.fetchAll(sourceIndex), //Fetch the trajectory events containing DOMSnapshots for mining.
                    sqliteService.saveClusteringInfo(clusteringId, threshold, numPerm, eps, minSamples) //Save the parameters used for this clustering.
            );
        }


        Future.all(todo)

                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(compositeFuture->{

                    Set<String> minedSnapshotIds = compositeFuture.resultAt(0);
                    List<JsonObject> events = compositeFuture.resultAt(1);

                    log.info(events.iterator().next().encodePrettily());



                    List<JsonObject> selectedEvents = new ArrayList<>();

                    for (JsonObject event : events) {
                        if (stateClusteringEventFilter().test(event) &&
                                !minedSnapshotIds.contains(event.getString("mongo_id"))) { //Skip snapshots that we've already mined.

                            selectedEvents.add(event);
                        }
                    }


                    ListIterator<JsonObject> it =  selectedEvents.listIterator();
                    Future<Void> f = null;
                    while(it.hasNext()){
                        JsonObject event = it.next();
                        String snapshotId = event.getString("mongo_id");
                        String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;
                        JsonObject domSnapshot = new JsonObject(event.getString("eventDetails_domSnapshot"));
                        String snapshotHtml = domSnapshot.getString("outerHTML");

                        if (f == null){
                            f = mineCommonSubstructures(clusteringId, snapshotId, baseURI, snapshotHtml, threshold, numPerm, eps,  minSamples);
                        }else{
                            f = f.compose(done->mineCommonSubstructures(clusteringId, snapshotId, baseURI, snapshotHtml, threshold, numPerm, eps, minSamples));
                        }
                    }

                    f.onFailure(err->log.error(err.getMessage(),err))
                            .onSuccess(done->{
                                log.info("Finished mining {} DOMSnapshots for common substructures", selectedEvents.size());
                                rc.getDelegate().response().setStatusCode(200).end();
                            });




                });

    }

    private Future<Void> mineCommonSubstructures(String clusteringId, String snapshotId, String baseURI, String snapshotHtml, double threshold, int numPerm, double dbscanEps, int dbscanMinSamples){
        return Future.all(
                        cleanerService.cleanHTML(snapshotHtml), //Get cleaned HTML snapshot
                        makeMinhashLSHForSnapshotNodes(snapshotId, baseURI, snapshotHtml, threshold, numPerm)
                )
                .onFailure(err->log.error(err.getMessage(),err))
                .compose(compositeFuture->{
                    String cleanedSnapshotHTML = (String) compositeFuture.list().get(0);
                    String lshId = (String) compositeFuture.list().get(1);

                    return Future.all(
                            getNodeClustering(lshId, Double.toString(dbscanEps), Integer.toString(dbscanMinSamples)),
                            Future.succeededFuture(cleanedSnapshotHTML),
                            Future.succeededFuture(lshId)
                    );

                })
                .compose(compositeFuture -> {
                    Map<String, List<JsonObject>> clusterMap = (Map<String, List<JsonObject>>) compositeFuture.list().get(0);

                    Document snapshotDocument = Jsoup.parse((String) compositeFuture.list().get(1));

                    String lshId = (String) compositeFuture.list().get(2);

                    return Future.all(
                            processNodeClusters(clusterMap, snapshotId, snapshotDocument),
                            Future.succeededFuture(lshId)
                    );


                })
                .compose(compositeFuture->{

                    List<JsonObject> annotationCandidates = (List<JsonObject>) compositeFuture.list().get(0);
                    String lshId = (String) compositeFuture.list().get(1);

                    JsonArray parentIds = annotationCandidates.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

                    log.info("Saving extracted substructures for snapshot: {} - {}", snapshotId, baseURI );
                    //Save all our work to SQLite
                    return Future.all(annotationCandidates.stream()
                            .map(candidate->{

                                List<Future<Void>> persistenceFutures = new ArrayList<>();
                                persistenceFutures.add(sqliteService.saveCommonSubstructureContainer(clusteringId, candidate));
                                persistenceFutures.addAll(candidate.getJsonArray("items").stream()
                                        .map(o->(JsonObject)o)
                                        .map(substructure->sqliteService.saveCommonSubstructure(clusteringId, substructure))
                                        .toList());

                                return Future.all(persistenceFutures);
                            })
                            .collect(Collectors.toList()))
                            //Tell OdoLSH to persist the parent nodes into redis
                            .compose(done->{


                                return webClient.post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId + "/fingerprints")
                                        .sendJson(parentIds)
                                        .onFailure(err->log.error(err.getMessage(),err))

                                ;

                            })

                            .compose(done->{
                                log.info("Finished mining common substructures for clustering {} with DOMSnapshot {} - {} ", clusteringId, snapshotId, baseURI);
                                return Future.succeededFuture();
                    });

                });
    }

    private Future<List<JsonObject>> processNodeClusters(Map<String, List<JsonObject>> clusterMap, String snapshotId, Document document){
        List<JsonObject> annotationCandidates = new  ArrayList<>();

        TagAndAttributeStrategy.LabelingVisitor labelingNodeVisitor = new TagAndAttributeStrategy.LabelingVisitor();
        document.traverse(labelingNodeVisitor);

        Iterator<Map.Entry<String, List<JsonObject>>> it = clusterMap.entrySet().iterator();

        if(clusterMap.isEmpty()){ //Handle case where no cluster were detected.
            return Future.succeededFuture(annotationCandidates);
        }

        Future<JsonObject> f = null;
        while (it.hasNext()) {
            Map.Entry<String, List<JsonObject>> cluster = it.next();
            if (f == null) {
                f = processNodeCluster(cluster, snapshotId, document);
            }else {
                f = f.compose(candidate->{
                    if (candidate != null){
                        annotationCandidates.add(candidate);
                    }
                    return processNodeCluster(cluster, snapshotId, document);
                });
            }
        }

        return f.compose(finalCandidate->{
            if(finalCandidate != null){
                annotationCandidates.add(finalCandidate);
            }

            //Resolve parent node ids.
            Iterator<JsonObject> candidateIterator = annotationCandidates.iterator();
            while (candidateIterator.hasNext()) {
                JsonObject candidate = candidateIterator.next();
                Element parentElement = document.selectXpath(candidate.getString("parentXpath")).get(0);
                int parentIndex = labelingNodeVisitor.nodeIndex.get(parentElement);
                candidate.put("parentIndex", parentIndex);
                candidate.put("parentId", candidate.getString("snapshotId") + "_"+ parentIndex);
            }


            return Future.succeededFuture(annotationCandidates);
        });

    }

    private Future<JsonObject> processNodeCluster(Map.Entry<String, List<JsonObject>> cluster, String snapshotId, Document document){
        Set<Element> parents = new HashSet<>();

        JsonArray clusterItems = cluster.getValue().stream()
                .filter(entry->entry.containsKey("robustXpath"))
                .map(item->{
                    String robustXpath = item.getString("robustXpath");
                    Element element = document.selectXpath(robustXpath).get(0);
                    if(element.hasParent()){
                        parents.add(element.parent());
                    }
                    return new JsonObject()
                            .put("snapshotId", snapshotId )
                            .put("clusterId",  cluster.getKey())
                            .put("nodeId", item.getString("id").split("_")[1])
                            .put("fullNodeId", item.getString("id"))
                            .put("robustXpath", robustXpath)
                            .put("html", element.outerHtml());
                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

        if(!clusterItems.isEmpty() && parents.size() == 1){
            Element parentElement = parents.iterator().next();


            return vertx.getDelegate().executeBlocking(()->{
                    try{
                        return computeXpathNoRoot(parentElement);
                        //return robulaPlus.getRobustXPath(parentElement, document);
                    }catch (IllegalStateException e){
                        return null;
                    }catch(IllegalArgumentException e){
                        return null;
                    }
                    })
                    .compose(parentXpath->{
                        JsonObject annotationCandidate = new JsonObject()
                                .put("snapshotId", snapshotId)
                                .put("clusterId", cluster.getKey())
                                .put("parentXpath", parentXpath != null? parentXpath : "null")
                                .put("parentHtml", parentElement.outerHtml())
                                .put("items", clusterItems);

                        return Future.succeededFuture(annotationCandidate);
                    });
        }else{
            return Future.succeededFuture(null);
        }
    }

    private Future<Map<String, List<JsonObject>>> getNodeClustering(String lshId, String dbscanEPS, String dbscanMinSamples ){
        log.info("Fetching node clustering from LSH: {}",lshId);
        return webClient.get(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/%s/clustering".formatted(lshId))
                .addQueryParam("eps", dbscanEPS)
                .addQueryParam("min_samples", dbscanMinSamples)
                .send()
                .onFailure(err->log.error(err.getMessage(), err))
                .compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .compose(response->{
                    JsonObject clusters = response.getJsonObject("clusters");
                    Map<String, List<JsonObject>> clusterMap = new HashMap<>();
                    clusters.forEach(entry->{
                        if(!entry.getKey().equals("-1")){ //Ignore noise cluster produced by DBSCAN
                            clusterMap.put(entry.getKey(), ((JsonArray)entry.getValue()).stream()
                                    .map(o->(JsonObject)o)
                                    .collect(Collectors.toList())
                            );
                        }
                    });

                    return Future.succeededFuture(clusterMap);
                })
        ;
    }

    private void buildStateClusters(RoutingContext rc){

        String sourceIndex = rc.queryParam("srcIndex").get(0);
        String lshName = rc.queryParam("targetLSH").get(0);
        double threshold = rc.queryParam("threshold").isEmpty()?0.5:Double.parseDouble(rc.queryParam("threshold").get(0));
        int numPerm = rc.queryParam("numPerm").isEmpty()?256:Integer.parseInt(rc.queryParam("numPerm").get(0));


        //Create an LSH model for clustering the DOMSnapshots.
        webClient
                .post(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH")
                .sendJson(new JsonObject()
                        .put("threshold", threshold)
                        .put("name", lshName)
                        .put("num_perm", numPerm)
                ).compose(response->Future.succeededFuture(response.bodyAsJsonObject()))
                .onFailure(err->log.error(err.getMessage(),err))
                .onSuccess(response->{
                    String lshId = response.getString("id");
                    log.info("New LSH created with id {}", lshId);
                    rc.put("lshId", lshId);
                    //Register an eventbus listener to process the documents/events from elasticsearch.
                    String eventBusAddress = "%s-hashingProcessor".formatted(lshId);

                    MessageConsumer eventBusListener = vertx.eventBus().consumer(eventBusAddress, msg->{
                        JsonObject event = (JsonObject) msg.body();
                        if(stateClusteringEventFilter().test(event)){

                            JsonObject domSnapshotData = new JsonObject(event.getString("eventDetails_domSnapshot"));
                            String baseURI = event.containsKey("eventDetails_element")?new JsonObject(event.getString("eventDetails_element")).getString("baseURI"):null;

                            cleanerService.toNodeLinks(domSnapshotData.getString("outerHTML"))
                                    .compose(nodeLinks->{
                                        nodeLinks.put("id", event.getString("mongo_id"));
                                        nodeLinks.put("baseURI", baseURI==null?"-":baseURI);

                                        return webClient.put(ODO_LSH_PORT, ODO_LSH_HOST, "/minhashLSH/" + lshId)
                                                .sendJson(new JsonArray().add(nodeLinks));
                                    });

                        }
                    });

                    elasticsearchService.processEvents(sourceIndex, eventBusAddress)
                            .onFailure(err->log.error(err.getMessage(), err))
                            .onSuccess(done->{
                                log.info("Done processing events, unregistering event bus listener!");
                                eventBusListener.getDelegate().unregister();
                                rc.next();
                            });


                });

    }

    /**
     * Returns a single DOMSnapshot from the provided elasticsearch index.
     * @param rc
     */
    private void sampleDOMSnapshot(RoutingContext rc) {
        String esIndex = rc.queryParam("index").get(0);
        String eventType = rc.queryParam("eventDetails_name").get(0);

        elasticsearchService.fetchAll(esIndex).onSuccess(events -> {
            log.info("got events");
            Iterator<JsonObject> it = events.iterator();
            JsonObject event = it.next();
            //Randomly look for an event that has the specified name and contains a DOMSnapshot
            while (!event.containsKey("eventDetails_domSnapshot") &&  event.getString("eventDetails_name").equals(eventType)) {
                log.info("contains eventDetails_domSnapshot: {}", event.containsKey("eventDetails_domSnapshot"));
                log.info("eventDetails_name: {}", event.getString("eventDetails_name"));
                event = it.next();
            }

            JsonObject data = new JsonObject(event.getString("eventDetails_domSnapshot"));
            rc.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(data.getString("outerHTML"));

        }).onFailure(throwable -> log.error(throwable.getMessage(), throwable));
    }

    private void nodeLinks(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.toNodeLinks(html)
                .onSuccess(graph -> rc.response().setStatusCode(200).end(graph.encodePrettily()))
                .onFailure(throwable -> log.error(throwable.getMessage(), throwable));
        ;
    }

    private void clean(RoutingContext rc) {

        String html = rc.body().asString();
        cleanerService.cleanHTML(html)
                .onSuccess(result -> rc.response().setStatusCode(200).end(result))
                .onFailure(ex -> rc.response().setStatusCode(500).end(ex.getMessage()));
        ;

    }
}
