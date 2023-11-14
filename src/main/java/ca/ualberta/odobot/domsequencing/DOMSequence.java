package ca.ualberta.odobot.domsequencing;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.ListIterator;

public class DOMSequence extends ArrayList<DOMSegment> {

    public static DOMSequence fromJson(JsonObject json){
        JsonArray sequenceData = json.getJsonArray("segments");
        var result = new DOMSequence();
        sequenceData.stream()
                .map(o->(JsonObject)o)
                .map(segmentData->new DOMSegment(segmentData.getString("tag"), segmentData.getString("class")))
                .forEach(segment->result.add(segment));

        return result;
    }

    public JsonObject toJson(){
        var result = new JsonObject();

        JsonArray sequenceData = new JsonArray();

        ListIterator<DOMSegment> it = listIterator();

        while (it.hasNext()){
            int nextIndex = it.nextIndex();
            DOMSegment segment = it.next();
            JsonObject segmentData = segment.toJson();
            segmentData.put("position", nextIndex);
            sequenceData.add(segmentData);
        }

        result.put("segments", sequenceData);
        result.put("sequence", toString());
        return result;
    }

    public String toString(){
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        forEach(segment->{
            sb.append(segment.tag() + (segment.className().isEmpty()?"":"["+segment.className()+"]") + "|");
        });

        return sb.toString();
    }

}
