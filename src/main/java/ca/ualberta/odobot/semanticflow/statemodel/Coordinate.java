package ca.ualberta.odobot.semanticflow.statemodel;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;


public class Coordinate {
    private static final Logger log = LoggerFactory.getLogger(Coordinate.class);

    public Coordinate parent;
    private Set<Coordinate> children = new HashSet<>();
    public String xpath;
    public int index;
    private UUID id;
    private List<CoordinateData> data = new ArrayList<>();

    public List<CoordinateData> getData() {
        return data;
    }

    public void setData(CoordinateData data) {
        this.data.add(data);
    }

    public void setData(List<CoordinateData> data){
        this.data = data;
    }

    public Coordinate(){};

    /**
     * Make a coordinate copy
     * @param coordinate to copy
     */
    public Coordinate(Coordinate coordinate){
        this.parent = coordinate.parent != null? new Coordinate(coordinate.parent): null;
        this.xpath = coordinate.xpath;
        this.index = coordinate.index;
        this.id = coordinate.id;
    }

    public Graph toGraph(){
        Graph g = new Graph();

        Set<Coordinate> coordinates = toSet(this);
        coordinates.forEach(coordinate -> g.addNode(coordinate));
        coordinates.forEach(parent -> parent.getChildren().forEach(child->g.addEdge(parent, child)));

        return g;
    }

    public Set<String> getXpaths(){
        return toSet(this).stream().map(c->c.xpath).collect(Collectors.toSet());
    }

    public Optional<Coordinate> getByXpath(String xpath){
        Set<Coordinate> coordinates = toSet(this);
        return coordinates.stream().filter(coordinate -> coordinate.xpath.equals(xpath)).findFirst();
    }



    public boolean isRoot(){
        return parent == null;
    }


    public Coordinate addChild(Coordinate child){
        children.add(child);
        return this;
    }

    public int numChildren(){
        return children.size();
    }

    public int hashCode(){
        return xpath.hashCode() ^ index ;
    }

    public boolean equals(Object o){
        if(o instanceof Coordinate){
            Coordinate other = (Coordinate) o;

            return this.xpath.equals(other.xpath);

        }
        return false;
    }




    public Set<Coordinate> getChildren() {
        return children;
    }

    /**
     *
     * @return A set containing this, and all child coordinates.
     */
    public Set<Coordinate> toSet(){
        Set<Coordinate> result = new HashSet<>();

        if(numChildren() > 0){
            for(Coordinate child: children){
                result.addAll(child.toSet());
            }
        }

        result.add(this);
        return result;

    }

    public Coordinate getRoot(){
        Coordinate curr = this;
        while (curr.parent != null){
            curr = curr.parent;
        }
        return curr;
    }

    public static Set<Coordinate> toSet(Coordinate coordinate){
        Coordinate root = coordinate.getRoot();
        return root.toSet();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("xpath", xpath)
                .put("index", index)
                .put("numChildren", numChildren());
        return result;
    }

    public String toString(){
        return toJson().encodePrettily();
    }

    public static Coordinate merge(Coordinate c1, Coordinate c2){
        Graph sourceGraph = c1.toGraph();
        Graph targetGraph = c2.toGraph();
        Graph result = Graph.merge(sourceGraph, targetGraph);
        Set<Coordinate> resultingCoordinates = result.toCoordinate();
        if(resultingCoordinates.size() > 1){
            log.warn("rootset size was {} after merge!", resultingCoordinates.size());
        }
        return resultingCoordinates.iterator().next();
    }

    //TODO - this doesn't make sense logically yet.
//    public static Coordinate split(Coordinate c1, Coordinate c2){
//        Graph sourceGraph = c1.toGraph();
//        Graph targetGraph = c2.toGraph();
//        Graph result = Graph.split(sourceGraph, targetGraph);
//        return result.toCoordinate();
//    }
}
