package ca.ualberta.odobot.semanticflow.statemodel;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * Encapsulates information we have about a coordinate
 */
public class CoordinateData {

//    public UUID stateId;
    private boolean isEventTarget;
    private String outerHTML;
    private String tag;
//    private JsonObject styleRules;
    private JsonObject attributes;
    private String eventId;

    public boolean isEventTarget() {
        return isEventTarget;
    }

    public String getAttribute(String attributeName){
        return attributes.getString(attributeName);
    }

    public void setEventTarget(boolean eventTarget) {
        isEventTarget = eventTarget;
    }

    public String getOuterHTML() {
        return outerHTML;
    }

    public void setOuterHTML(String outerHTML) {
        this.outerHTML = outerHTML;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public JsonObject getAttributes() {
        return attributes;
    }

    public void setAttributes(JsonObject attributes) {
        this.attributes = attributes;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public int hashCode(){
        return outerHTML.hashCode();
    }

    public boolean equals(Object o){
        if(o instanceof CoordinateData){
            CoordinateData other = (CoordinateData) o;
            return getOuterHTML().equals(other.getOuterHTML());
        }else{
            return false;
        }
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("tag", getTag());
        result.put("attributes", getAttributes());
        result.put("eventId", getEventId());
        result.put("isEventTarget", isEventTarget());
        if (getOuterHTML() != null){
            result.put("outerHTML", getOuterHTML());
        }
        return result;
    }

    public String toString(){
        return toJson().encodePrettily();
    }
}
