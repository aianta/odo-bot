package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    public boolean isStateless(){
        return stateId == null;
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
        if(stateId != null){
            return xpath.hashCode() ^ stateId.hashCode() ^ index;
        }else{
            return xpath.hashCode() ^ index;
        }

    }

    public boolean equals(Object o){
        if(o instanceof Coordinate){
            Coordinate other = (Coordinate) o;
            return this.xpath.equals(other.xpath) && this.stateId.equals(other.stateId);
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

}
