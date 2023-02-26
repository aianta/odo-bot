package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.UUID;

/**
 * A timeline corresponds with an ordered list of parsed semantic artifacts from an underlying list of events.
 */
public class Timeline extends ArrayList<TimelineEntity> {

    private UUID id = UUID.randomUUID();
    private JsonObject annotations;

    public UUID getId() {
        return id;
    }

    public JsonObject getAnnotations() {
        if(annotations == null){ //Init annotations object if null.
            annotations = new JsonObject().put("id", getId().toString());
        }
        return annotations;
    }

    public void setAnnotations(JsonObject annotations) {
        this.annotations = annotations;
    }

    /**
     * Return the last element in the timeline.
     * @return the last element in the timeline.
     */
    public TimelineEntity last(){
        return isEmpty()?null:get(size()-1);
    }

    /**
     * Returns a json representation of the timeline.
     * @return
     */
    public JsonObject toJson(){
        JsonObject result = new JsonObject();

        JsonArray entityData = new JsonArray();
        ListIterator<TimelineEntity> it = listIterator();
        while (it.hasNext()){
            int index = it.nextIndex();
            TimelineEntity entity = it.next();

            JsonObject data = new JsonObject();
            data.put("symbol", entity.symbol());
            data.put("size", entity.size());
            data.put("index", index);
            data.put("terms", entity.terms().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            data.put("cssClassTerms", entity.cssClassTerms().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            data.put("idTerms", entity.idTerms().stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
            data.mergeIn(entity.toJson()); //Bring in entity specific data.
            entityData.add(data);
        }

        result.put("id", id.toString());
        result.put("string", toString());
        result.put("data", entityData);

        return result;
    }

    public String toString(){
        StringBuilder sb = new StringBuilder();
        Iterator<TimelineEntity> it = iterator();
        while (it.hasNext()){
            TimelineEntity entity = it.next();
            sb.append("|" + entity.symbol() + (entity.size() > 1?(":" + entity.size()):""));

            if(!it.hasNext()){
                sb.append("|");
            }
        }
        return sb.toString();
    }

}
