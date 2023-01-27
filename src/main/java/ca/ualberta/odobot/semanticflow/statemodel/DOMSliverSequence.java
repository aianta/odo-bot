package ca.ualberta.odobot.semanticflow.statemodel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.UUID;


/**
 * Represents a sequence of DOMSlivers as they were extracted from logs.
 *
 */
public class DOMSliverSequence extends ArrayList<Coordinate> {
    private static final Logger log = LoggerFactory.getLogger(DOMSliverSequence.class);

    private UUID id = UUID.randomUUID();

    public UUID getId(){
        return id;
    }

    /**
     * Merges a coordinate into the last coordinate in the sequence. Incrementing the sequence size by 1.
     * @param coordinate the coordinate to merge into the sequence.
     */
    public void merge(Coordinate coordinate){
        if(size() == 0){
            add(coordinate);
        }else{
            Coordinate last = get(size() - 1);
            Coordinate next = Coordinate.merge(last,  coordinate);
            add(next);
        }
    }
}
