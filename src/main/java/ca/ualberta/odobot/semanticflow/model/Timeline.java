package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * A timeline corresponds with an ordered list of parsed semantic artifacts from an underlying list of events.
 */
public class Timeline extends ArrayList<TimelineEntity> {

    /**
     * Return the last element in the timeline.
     * @return the last element in the timeline.
     */
    public TimelineEntity last(){
        return isEmpty()?null:get(size()-1);
    }

    public JsonObject toJson(){
        return null;
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
