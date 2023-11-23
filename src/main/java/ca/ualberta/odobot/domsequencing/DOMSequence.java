package ca.ualberta.odobot.domsequencing;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ListIterator;

public class DOMSequence extends ArrayList<DOMSegment> {

    public DOMSequence(){
        super();
    }

    public DOMSequence(Collection<DOMSegment> c){
        super(c);
    }


    public int hashCode(){
        HashCodeBuilder builder = new HashCodeBuilder(21, 31);
        forEach(segment->builder.append(segment.hashCode()));
        return builder.toHashCode();
    }

    public boolean equals(Object o){
        //Must be a DOMSequence
        if(!(o instanceof DOMSequence)){
            return false;
        }

        DOMSequence other = (DOMSequence) o;

        //Other DOMSequence must have the same size (number of DOM segments)
        if(other.size() != this.size()){
            return false;
        }

        //The segments at each index must match
        for(int i = 0; i < this.size(); i++){
            DOMSegment thisSegment = this.get(i);
            DOMSegment otherSegment = other.get(i);

            if(!thisSegment.equals(otherSegment)){
                return false;
            }
        }

        return true;
    }

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

    public String toStringNoClasses(){
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        forEach(segment->{
            sb.append(segment.tag() + "|");
        });

        return sb.toString();
    }

    public String toString(){
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        forEach(segment->{
            sb.append(segment.tag() + (segment.className().isEmpty()?"":"["+segment.className()+"]") + "|");
        });

        return sb.toString();
    }

//    public String toString(){
//        StringBuilder sb = new StringBuilder();
//        sb.append("|");
//        forEach(segment->{
//            sb.append(segment.tag() + "|");
//        });
//
//        return sb.toString();
//    }

}
