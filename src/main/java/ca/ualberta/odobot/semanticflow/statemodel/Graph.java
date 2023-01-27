package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.ModelManager;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Graph {
    private static final Logger log = LoggerFactory.getLogger(Graph.class);

    //Keep track of xpath -> coordinateData
    private Multimap<String, CoordinateData> dataMap = ArrayListMultimap.create();

    private Set<String> nodes = new HashSet<>();
    private Set<Edge> edges = new HashSet<>();

    public Collection<CoordinateData> getData(String xpath){
        return dataMap.get(xpath);
    }

    public Graph addNode(Coordinate coordinate){
        dataMap.putAll(coordinate.xpath, coordinate.getData());
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

    /**
     * Removes graph g2 from graph g1
     * @param g1 graph to be removed from
     * @param g2 graph to remove
     *
     * TODO - this method isn't conceptually sound, need to rethink.
     */
    @Deprecated
    public static Graph split (Graph g1, Graph g2){
        Graph result = new Graph();

        Set<String> nodeIntersection = new HashSet<String>(g1.getNodes());
        nodeIntersection.retainAll(g2.getNodes());

        Set<Edge> edgeIntersection = new HashSet<>(g1.getEdges());
        edgeIntersection.retainAll(g2.getEdges());

        Set<String> nodesInJustG2 = new HashSet<>(g2.getNodes());
        nodesInJustG2.removeAll(nodeIntersection);

        Set<Edge> edgesInJustG2 = new HashSet<>(g2.getEdges());
        edgesInJustG2.removeAll(edgeIntersection);

        Set<String> nodesInG1notInG2 = g1.getNodes().stream().filter(node->!nodesInJustG2.contains(node)).collect(Collectors.toSet());
        Set<Edge> edgesInG1notInG2 = g1.getEdges().stream().filter(edge->!edgesInJustG2.contains(edge)).collect(Collectors.toSet());

        result.nodes.addAll(nodesInG1notInG2);
        result.edges.addAll(edgesInG1notInG2);

        return result;
    }

    public static Graph merge(Graph g1, Graph g2){
        Graph result = new Graph();
        result.dataMap.putAll(g1.dataMap);
        result.dataMap.putAll(g2.dataMap);
        result.nodes.addAll(g1.getNodes());
        result.nodes.addAll(g2.getNodes());
        result.edges.addAll(g1.getEdges());
        result.edges.addAll(g2.getEdges());
        return result;
    }

    public Graph split(Graph g){
        Set<String> nodeIntersection = new HashSet<String>(getNodes());
        nodeIntersection.retainAll(g.getNodes());

        Set<Edge> edgeIntersection = new HashSet<>(getEdges());
        edgeIntersection.retainAll(g.getEdges());

        Set<String> nodesInJustG = new HashSet<>(g.getNodes());
        nodesInJustG.removeAll(nodeIntersection);

        Set<Edge> edgesInJustG = new HashSet<>(g.getEdges());
        edgesInJustG.removeAll(edgeIntersection);

        Set<String> nodesInG1notInG2 = getNodes().stream().filter(node->!nodesInJustG.contains(node)).collect(Collectors.toSet());
        Set<Edge> edgesInG1notInG2 = getEdges().stream().filter(edge->!edgesInJustG.contains(edge)).collect(Collectors.toSet());

        nodes.clear();
        edges.clear();

        nodes.addAll(nodesInG1notInG2);
        edges.addAll(edgesInG1notInG2);

        return this;
    }

    public Graph merge(Graph g){
        dataMap.putAll(g.dataMap);
        nodes.addAll(g.getNodes());
        edges.addAll(g.getEdges());
        return this;
    }

    /**
     * Constructs a coordinate (rooted-tree) from the graph.
     * @return
     */
    public Set<Coordinate> toCoordinate(){


        Map<String, Coordinate> nodeMap = new HashMap<>();

        for (String node: nodes){
            Coordinate c = new Coordinate();
            c.xpath = node;
            c.index = ModelManager.getIndexFromXpath(c.xpath);

            List<CoordinateData> coordinateData = dataMap.get(node).stream().toList();
            c.setData(coordinateData);

            nodeMap.put(node, c);
        }

        for(Edge e: edges){
            Coordinate n1 = nodeMap.get(e.source);
            Coordinate n2 = nodeMap.get(e.target);

            n1.addChild(n2);
            n2.parent = n1;
        }

        Set<String> rootSet = getRootSet();
        log.info("rootSet size: {}", rootSet.size());

        //NOTE: Attach points might have as many roots as leaves.
        Set<Coordinate> result = rootSet.stream().map(rootPath->nodeMap.get(rootPath)).collect(Collectors.toSet());


        // return the root coordinate
        return result;
    }

    /**
     * Returns the difference graph of two graphs.
     * @param g1
     * @param g2
     * @return graph g1 - g2
     */
    public static Graph diff(Graph g1, Graph g2){
        Graph result = new Graph();

        Set<String> nodeIntersection = new HashSet<>(g1.nodes);
        nodeIntersection.retainAll(g2.nodes);

        Set<String> nodesInG2NotInG1 = g2.nodes.stream().filter(node->!nodeIntersection.contains(node)).collect(Collectors.toSet());
        Set<String> nodesInG1NotInG2 = g1.nodes.stream().filter(node->!nodeIntersection.contains(node)).collect(Collectors.toSet());

        result.nodes.addAll(nodesInG1NotInG2);
        result.nodes.addAll(nodesInG2NotInG1);

        Set<Edge> edgeIntersection = new HashSet<>(g1.edges);
        edgeIntersection.retainAll(g2.edges);

        Set<Edge> edgesInG2NotInG1 = g2.edges.stream().filter(edge->!edgeIntersection.contains(edge)).collect(Collectors.toSet());;
        Set<Edge> edgesInG1NotInG2 = g1.edges.stream().filter(edge->!edgeIntersection.contains(edge)).collect(Collectors.toSet());

        result.edges.addAll(edgesInG1NotInG2);
        result.edges.addAll(edgesInG2NotInG1);

        return result;
    }

    public boolean equals(Object o){
        //If the object isn't a graph it isn't equal.
        if(!(o instanceof Graph)){
            return false;
        }
        Graph other = (Graph) o;

        return nodes.containsAll(other.nodes) &&
                edges.containsAll(other.edges) &&
                nodes.size() == other.nodes.size() &&
                edges.size() == other.edges.size();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("nodes", nodes.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        result.put("nodeCount", nodes.size());
        result.put("edges", edges.stream().map(e->e.toString()).collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
        result.put("edgeCount", edges.size());

        return result;
    }

    public Set<String> getRootSet(){
        //Find the root of the graph. The node(s) that do not appear in the valueset of edges.
        Set<String> keyset = edges.stream().map(edge -> edge.source).collect(Collectors.toSet());
        Set<String> valueset = edges.stream().map(edge-> edge.target).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(keyset);
        intersection.retainAll(valueset);

        Set<String> rootSet = keyset.stream().filter(xpath->!valueset.contains(xpath)).collect(Collectors.toSet());

        return rootSet;
    }

    public String toString(){
        return toJson().encodePrettily();
    }

}
