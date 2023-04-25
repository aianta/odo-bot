package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ListIterator;
import java.util.UUID;

public class SimpleStateModelParser {

    private static final Logger log = LoggerFactory.getLogger(SimpleStateModelParser.class);

    UUID modelId = UUID.randomUUID();
    final Neo4JUtils db;

    public SimpleStateModelParser(){
        this.db = new Neo4JUtils("bolt://localhost", "neo4j","neo4j123");
    }

    public void createModel(){
        this.modelId = UUID.randomUUID();
    }

    public void parseTimeline(Timeline t){

        ListIterator<TimelineEntity> it = t.listIterator();
        UUID previousState = null;
        while (it.hasNext()){

            int index = it.nextIndex();
            TimelineEntity e = it.next();

            UUID stateId = db.createState(modelId);
            db.createTimelineEntity(e,t, modelId);
            db.connectStateToEntity(stateId, t.getId(), index);

            //Hook up the previous entity to the current state, if this isn't the first state.
            if(previousState != null){
                db.connectEntityToState(t.getId(), index-1, stateId);
            }

            if(!it.hasNext()){
                var lastState = db.createState(modelId);
                db.connectEntityToState(t.getId(), index, lastState);
            }

            previousState = stateId;
        }



    }

}
