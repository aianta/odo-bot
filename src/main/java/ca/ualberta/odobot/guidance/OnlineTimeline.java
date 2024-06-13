package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Extension of the timeline class to allow for the notification of new timeline entities as they are added.
 */
public class OnlineTimeline extends Timeline {

    private static final Logger log = LoggerFactory.getLogger(OnlineTimeline.class);

    private List<Consumer<TimelineEntity>> consumerList = new ArrayList<>();

    public boolean add(TimelineEntity e){
        consumerList.forEach(consumer->consumer.accept(e));
        return super.add(e);
    }

    public void addListener(Consumer<TimelineEntity> consumer){
        consumerList.add(consumer);
    }



}
