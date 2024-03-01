package ca.ualberta.odobot.semanticflow.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Time;
import java.util.*;

/**
 * A timeline corresponds with an ordered list of parsed semantic artifacts from an underlying list of events.
 */
public class Timeline extends ArrayList<TimelineEntity> {

    private static final Logger log = LoggerFactory.getLogger(Timeline.class);

    private UUID id = UUID.randomUUID();
    private JsonObject annotations;

    public UUID getId() {
        return id;
    }

    /**
     * OPTIMIZATION FOR DATA EXTRACTION
     * We only really care about click events if they are the last click event preceding a network event.
     * Otherwise, we'd prefer to discard them, as it takes a lot of compute to determine ranked terms. for unnecessay click events.
     */
    public void pruneNonPrecedingClickEvents(){

        int originalSize = size();
        log.info("Original timeline size: {}", originalSize);

        List<Integer> clickEventIndices = indexList("CE");
        log.info("Click Events found at indices: {}", clickEventIndices);
        List<Integer> networkRequestIndices = indexList("NET");
        log.info("Network Events found at indices: {}", networkRequestIndices);

        Set<Integer> toKeep = new HashSet<>();

        networkRequestIndices.forEach(networkIndex->{

            int closestIndex = -1;

            for(Integer index: clickEventIndices){
                if(index > closestIndex && index < networkIndex){
                    closestIndex = index;
                }
            }

            toKeep.add(closestIndex);

        });

        log.info("Closest Click Events at: {}", toKeep);

        Set<TimelineEntity> toKeepSet = new HashSet<>();
        for(Integer index: toKeep){
            toKeepSet.add(get(index));
        }

        Iterator<TimelineEntity> it = iterator();
        while (it.hasNext()){
            TimelineEntity curr = it.next();
            if(curr.symbol().equals("CE") && !toKeepSet.contains(curr)){
                it.remove();
            }
        }

        log.info("removed {} click events!", originalSize - size());
    }

    private List<Integer> indexList(String symbol){

        List<Integer> result = new ArrayList<>();

        ListIterator<TimelineEntity> it = listIterator();
        while (it.hasNext()){
            TimelineEntity curr = it.next();





            if(curr.symbol().equals(symbol)){
                //We only care about non-get network requests
                if(curr.symbol().equals("NET")){
                    NetworkEvent networkEvent = (NetworkEvent)curr;
                    if(!networkEvent.getMethod().equals("GET")){
                        result.add(it.previousIndex());
                    }

                }else{
                    result.add(it.previousIndex());
                }

            }




        }

        return result;
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
            data.put("id", id.toString()+"#"+index);
            data.put("timestamp_milli", entity.timestamp());
            data.put("symbol", entity.symbol());
            data.put("size", entity.size());
            data.put("index", index);
            data.mergeIn(entity.getSemanticArtifacts());
            data.mergeIn(entity.toJson()); //Bring in entity specific data.
            entityData.add(data);
        }

        result.put("id", id.toString());
        result.put("annotations", getAnnotations());
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
