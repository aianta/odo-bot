package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

/**
 * Describes edges coming out of {@link State}
 */
public interface OutEdge {

    State source();
    TimelineEntity target();
}
