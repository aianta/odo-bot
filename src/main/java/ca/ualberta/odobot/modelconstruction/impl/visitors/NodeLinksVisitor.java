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

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;

public class NodeLinksVisitor implements NodeVisitor {

    private static ThreadPoolExecutor executor;

    int textNodeCounter = 0;
    private Function<Node, String> nodeToColorFunction;

    Map<Integer, Node> nodeMap;
    Map<Node, Integer> nodeIndex;
    Map<Integer, String> colorMap = new HashMap<>();
    Map<Integer, String> robulaMap = new HashMap<>();
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

        public ComputeRobustXpathTask(Element element, Document document){
            this.document = document;
            this.element = element;
        }

        @Override
        public void run() {
            try{
                String robustXpath = robulaPlus.getRobustXPath(element, document);
                log.info("Computed Robust Xpath: {}", robustXpath);
                promise.complete(robustXpath);
            }catch (IllegalStateException e){
                promise.complete(null);
            }
        }

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

            ComputeRobustXpathTask task = new ComputeRobustXpathTask((Element) node, document);
            robustXpathFutures.add(task.future().compose(robustXpath ->{
                if (robustXpath != null){
                    robulaMap.put(currNodeNumber, robustXpath);
                }
                return Future.succeededFuture();
            } ));

            executor.execute(task);

        }

        for(Node child: node.childNodes()){
            links.add(new JsonObject().put("source", currNodeNumber.toString()).put("target", nodeIndex.get(child).toString()));
        }
    }

    public Future<JsonObject> getGraphObject(){

        //Wait for all robust xpaths to be finish computation
        return Future.all(robustXpathFutures).compose(done -> {

            return Future.succeededFuture(new JsonObject()
                    .put("nodes", colorMap.entrySet().stream().map(nodeEntry->{
                        JsonObject nodeJson =  new JsonObject()
                                .put("id", nodeEntry.getKey().toString())
                                .put("color",  colorMap.get(nodeEntry.getKey()));
                        if(robulaMap.containsKey(nodeEntry.getKey())){
                            nodeJson.put("robustXpath", robulaMap.get(nodeEntry.getKey()));
                        }
                        return nodeJson;
                    }).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                    .put("links", links));

        });



    }

}
