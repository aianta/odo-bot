package ca.ualberta.odobot.modelconstruction.impl.visitors;

import ca.ualberta.odobot.common.RobulaPlus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static ca.ualberta.odobot.semanticflow.Utils.computeXpathNoRoot;

public class NodeLinksVisitor implements NodeVisitor {

    private static final Logger log = LoggerFactory.getLogger(NodeLinksVisitor.class);

    private static ThreadPoolExecutor executor;

    int textNodeCounter = 0;
    private Function<Node, String> nodeToColorFunction;

    Map<Integer, Node> nodeMap;
    Map<Node, Integer> nodeIndex;
    Map<Integer, String> colorMap = new HashMap<>();
    Map<Integer, String> robulaMap = new HashMap<>();
    Map<Integer, String> originalXpathMap = new HashMap<>();
    public NodeLinksVisitor(Function<Node, String> nodeToColorFunction) {}
    JsonArray links = new JsonArray();
    RobulaPlus robulaPlus = new RobulaPlus();
    Document document;
    private Vertx vertx;
    private List<Future<String>> robustXpathFutures = new ArrayList<>();

    public NodeLinksVisitor(Function<Node, String> nodeToColorFunction, Map<Integer, Node> nodeMap, Map<Node, Integer> nodeIndex, Document document, Vertx vertx) {
        this.nodeToColorFunction = nodeToColorFunction;
        this.nodeMap = nodeMap;
        this.nodeIndex = nodeIndex;
        this.document = document;
        this.vertx = vertx;

        if (executor == null) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(6);
        }
    }

    /**
     * Class wrapping Robula computation into a Runnable so it can be multithreaded.
     */
    private class ComputeRobustXpathTask implements Runnable {
        private static final Logger log = LoggerFactory.getLogger(ComputeRobustXpathTask.class);
        private Element element;
        private Document document;
        private RobulaPlus robulaPlus = new RobulaPlus();
        private Promise<String> promise = Promise.promise();
        private Instant start;

        private static final List<Long> computeTimes = new ArrayList<>();

        public ComputeRobustXpathTask(Element element, Document document){
            this.document = document;
            this.element = element;
        }

        public long maxExecutionTime(){
            return computeTimes.stream().mapToLong(l->l).max().orElse(0);
        }

        public double averageExecutionTime(){
            DecimalFormat df = new DecimalFormat("#.##");
            double meanTime = computeTimes.stream().mapToLong(l->l).average().getAsDouble();
            return Double.valueOf(df.format(meanTime));
        }

        //For debugging
        private long executionTime(){
            Instant now = Instant.now();
            return now.toEpochMilli() - start.toEpochMilli();
        }

        private void recordComputeTime(long time){
            computeTimes.add(time);
            if(computeTimes.size() > 1000){
                computeTimes.remove(0);
            }
        }

        @Override
        public void run() {
            start = Instant.now();
            try{
                String robustXpath = robulaPlus.getRobustXPath(element, document);
                long thisTime = executionTime();
                recordComputeTime(thisTime);
                log.info("[Execution time| This: {}ms | Mean: {}ms | Max:{}ms] Computed Robust Xpath: {}", thisTime, averageExecutionTime(), maxExecutionTime(), robustXpath);
                promise.complete(robustXpath);
            }catch (IllegalStateException e){
                promise.complete(null);
                recordComputeTime(executionTime());
            }catch(IllegalArgumentException e){
                promise.complete(null);
                recordComputeTime(executionTime());
            }
        }

        public Promise<String> promise() {return promise;}

        public Future<String> future(){
            return promise.future();
        }
    }

    @Override
    public void head(Node node, int i) {
        String currNodeColor = nodeToColorFunction.apply(node);
        Integer currNodeNumber = nodeIndex.get(node);
        colorMap.put(currNodeNumber, currNodeColor);

        if (node instanceof Element){
            Element element = (Element) node;
            try{
                String xpath = computeXpathNoRoot(element);
                if (element.equals(document.selectXpath(xpath).get(0))) {
                    robulaMap.put(currNodeNumber, xpath);
                    //If an original xpath has been saved for this element, keep track of it in the originalXpathMap
                    if(element.hasAttr("oxp")){
                        originalXpathMap.put(currNodeNumber, element.attr("oxp"));

                    }

                }
            }catch (Exception e){
                //Not a big deal if we fail on some small number of elements
                //log.warn(e.getMessage(), e);
            }


//            ComputeRobustXpathTask task = new ComputeRobustXpathTask((Element) node, document);
//            robustXpathFutures.add(task.future().compose(robustXpath ->{
//                if (robustXpath != null && document.selectXpath(robustXpath).get(0).equals((Element) node)){
//                    robulaMap.put(currNodeNumber, robustXpath);
//                }
//
//                if(robustXpath != null && !document.selectXpath(robustXpath).get(0).equals((Element) node)){
//                    log.error("Robula Error! Could not find element using robust xpath! {}", robustXpath);
//                }
//                return Future.succeededFuture();
//            } ));
//
//            /**
//             * On certain DOM Snapshots it has been observed that computing a robust xpath takes an enormous amount of time.
//             * TODO: investigate this.
//             * In the meantime timeout on long running tasks.
//             */
//            vertx.executeBlocking(() -> {
//                java.util.concurrent.Future threadFuture = executor.submit(task);
//                try {
//                    threadFuture.get(500, TimeUnit.MILLISECONDS); //Don't spend a crazy amount of time trying for robust xpaths here...
//                } catch (InterruptedException e) {
//                    task.promise().tryComplete();
//
//                } catch (ExecutionException e) {
//                    log.error(e.getMessage(), e);
//                    task.promise().tryComplete();
//
//                } catch (TimeoutException e) {
//                    Element element = (Element) node;
//                    String xpath = computeXpathNoRoot(element);
//                    if (element.equals(document.selectXpath(xpath).get(0))) {
//                        robulaMap.put(currNodeNumber, xpath);
//                    } else {
//                        log.error("Xpath error! Could not find element using xpath: {}", xpath);
//                    }
//                    ;
//
//                    log.info("Computing Robust Xpath timed out, instead using: {}", xpath);
//
//                    threadFuture.cancel(true);
//                    task.promise().tryComplete();
//
//                }
//                        return null;
//                    }).onFailure(err->log.error(err.getMessage(), err))
//                    .onSuccess(done->{
//                //NOP
//            });


        }

        for(Node child: node.childNodes()){
            links.add(new JsonObject().put("source", currNodeNumber.toString()).put("target", nodeIndex.get(child).toString()));
        }
    }

    public Future<JsonObject> getGraphObject(){

        return Future.succeededFuture(new JsonObject()
                .put("nodes", colorMap.entrySet().stream().map(nodeEntry->{
                    JsonObject nodeJson =  new JsonObject()
                            .put("id", nodeEntry.getKey().toString())
                            .put("color",  colorMap.get(nodeEntry.getKey()));
                    if(robulaMap.containsKey(nodeEntry.getKey())){
                        nodeJson.put("robustXpath", robulaMap.get(nodeEntry.getKey()));
                    }
                    if(originalXpathMap.containsKey(nodeEntry.getKey())){
                        nodeJson.put("oxp", originalXpathMap.get(nodeEntry.getKey()));
                    }
                    return nodeJson;
                }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("links", links));

        //Wait for all robust xpaths to be finish computation
//        return Future.all(robustXpathFutures).compose(done -> {
//
//            return Future.succeededFuture(new JsonObject()
//                    .put("nodes", colorMap.entrySet().stream().map(nodeEntry->{
//                        JsonObject nodeJson =  new JsonObject()
//                                .put("id", nodeEntry.getKey().toString())
//                                .put("color",  colorMap.get(nodeEntry.getKey()));
//                        if(robulaMap.containsKey(nodeEntry.getKey())){
//                            nodeJson.put("robustXpath", robulaMap.get(nodeEntry.getKey()));
//                        }
//                        return nodeJson;
//                    }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
//                    .put("links", links));
//
//        });



    }

}
