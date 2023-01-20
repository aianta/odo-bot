package ca.ualberta.odobot.semanticflow;

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
    public UUID stateId;
    private boolean isEventTarget;
    private String outerHTML;
    private String tag;
    private JsonObject styleRules;
    private JsonObject attributes;
    private CoordinateType type;

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
        this.stateId = coordinate.stateId;
        this.isEventTarget = coordinate.isEventTarget;
        this.outerHTML = coordinate.outerHTML;
        this.tag = coordinate.tag;
        this.styleRules = coordinate.styleRules;
        this.attributes = coordinate.attributes;
        this.type = coordinate.type;

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

    public boolean isStateless(){
        return stateId == null;
    }

    public boolean isRoot(){
        return parent == null;
    }

    public Coordinate addChildren(Set<Coordinate> children){
        this.children.addAll(children);
        return this;
    }

    public Coordinate addChild(Coordinate child){
        children.add(child);
        return this;
    }

    public int numChildren(){
        return children.size();
    }

    public int hashCode(){
        if(stateId != null){
            return xpath.hashCode() ^ stateId.hashCode() ^ index;
        }else{
            return xpath.hashCode() ^ index;
        }

    }

    public boolean equals(Object o){
        if(o instanceof Coordinate){
            Coordinate other = (Coordinate) o;

            /*
                For two coordinates to be the same they must have the same children
             */
            Set<String> childXpaths = getChildren().stream().map(c->c.xpath).collect(Collectors.toSet());
            Set<String> otherXpaths = other.getChildren().stream().map(c->c.xpath).collect(Collectors.toSet());

            Set<String> intersection = new HashSet<>(childXpaths);
            intersection.retainAll(otherXpaths);

            if(intersection.size() != childXpaths.size()){
                return false;
            }

            if (this.stateId != null && other.stateId != null){
                return this.xpath.equals(other.xpath) && this.stateId.equals(other.stateId);
            }else{
                return this.xpath.equals(other.xpath);
            }
        }
        return false;
    }

    /**
     * The log events from which this coordinate originates.
     */
    private Set<JsonObject> evidence = new HashSet<>();

    public void submitEvidence(JsonObject event){

    }

    public boolean hasStyleRule(String property){
        return styleRules.containsKey(property);
    }

    public String getAttribute(String attributeName){
        return attributes.getString(attributeName);
    }

    public Set<Coordinate> getChildren() {
        return children;
    }

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

    public CoordinateType getType() {
        return type;
    }

    public void setType(CoordinateType type) {
        this.type = type;
    }
}
