package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Graph {
    private static final Logger log = LoggerFactory.getLogger(Graph.class);

    private Set<String> nodes = new HashSet<>();
    private Set<Edge> edges = new HashSet<>();

    public Graph addNode(Coordinate coordinate){
        this.nodes.add(coordinate.xpath);
        return this;
    }

    public Graph addEdge(Coordinate source, Coordinate target){
        this.edges.add(new Edge(source.xpath, target.xpath));
        return this;
    }

    public Set<String> getNodes(){
        return nodes;
    }

    public Set<Edge> getEdges(){
        return edges;
    }

    public static Graph merge(Graph g1, Graph g2){
        Graph result = new Graph();
        result.nodes.addAll(g1.getNodes());
        result.nodes.addAll(g2.getNodes());
        result.edges.addAll(g1.getEdges());
        result.edges.addAll(g2.getEdges());
        return result;
    }

    public Graph merge(Graph g){
        nodes.addAll(g.getNodes());
        edges.addAll(g.getEdges());
        return this;
    }

    /**
     * Constructs a coordinate (rooted-tree) from the graph.
     * @return
     */
    public Coordinate toCoordinate(){


        Map<String, Coordinate> nodeMap = new HashMap<>();

        for (String node: nodes){
            Coordinate c = new Coordinate();
            c.xpath = node;
            c.index = ModelManager.getIndexFromXpath(c.xpath);
            nodeMap.put(node, c);
        }

        for(Edge e: edges){
            Coordinate n1 = nodeMap.get(e.source);
            Coordinate n2 = nodeMap.get(e.target);

            n1.addChild(n2);
            n2.parent = n1;
        }

        //Find the root of the graph. The node that does not appear in the valueset of edges.
        Set<String> keyset = edges.stream().map(edge -> edge.source).collect(Collectors.toSet());
        Set<String> valueset = edges.stream().map(edge-> edge.target).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(keyset);
        intersection.retainAll(valueset);

        Set<String> rootSet = keyset.stream().filter(xpath->!valueset.contains(xpath)).collect(Collectors.toSet());
        log.info("rootSet size: {}", rootSet.size());

        String rootXpath = rootSet.iterator().next();

        // return the root coordinate
        return nodeMap.get(rootXpath);
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("nodes", nodes.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        result.put("edges", edges.stream().map(e->e.toString()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        return result;
    }

    public String toString(){
        return toJson().encodePrettily();
    }

}
