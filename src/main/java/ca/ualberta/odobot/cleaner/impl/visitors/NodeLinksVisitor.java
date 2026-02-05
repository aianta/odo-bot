package ca.ualberta.odobot.cleaner.impl.visitors;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.util.*;
import java.util.function.Function;

public class NodeLinksVisitor implements NodeVisitor {

    int textNodeCounter = 0;
    private Function<Node, String> nodeToColorFunction;

    Map<Integer, Node> nodeMap;
    Map<Node, Integer> nodeIndex;
    Map<Integer, String> colorMap = new HashMap<>();
    public NodeLinksVisitor(Function<Node, String> nodeToColorFunction) {}
    JsonArray links = new JsonArray();

    public NodeLinksVisitor(Function<Node, String> nodeToColorFunction, Map<Integer, Node> nodeMap, Map<Node, Integer> nodeIndex) {
        this.nodeToColorFunction = nodeToColorFunction;
        this.nodeMap = nodeMap;
        this.nodeIndex = nodeIndex;
    }

    @Override
    public void head(Node node, int i) {
        String currNodeColor = nodeToColorFunction.apply(node);
        Integer currNodeNumber = nodeIndex.get(node);
        colorMap.put(currNodeNumber, currNodeColor);

        for(Node child: node.childNodes()){
            links.add(new JsonObject().put("source", currNodeNumber.toString()).put("target", nodeIndex.get(child).toString()));
        }
    }

    public JsonObject getGraphObject(){
        return new JsonObject()
                .put("nodes", colorMap.entrySet().stream().map(nodeEntry->
                        new JsonObject()
                                .put("id", nodeEntry.getKey().toString())
                                .put("color",  colorMap.get(nodeEntry.getKey()))
                ).collect(JsonArray::new, JsonArray::add, JsonArray::addAll))
                .put("links", links);

    }

}
