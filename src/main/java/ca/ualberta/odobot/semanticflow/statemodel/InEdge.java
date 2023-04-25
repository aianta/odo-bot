package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

/**
 * Describes edges going into {@link State}
 */
public interface InEdge {

    TimelineEntity source();
    State target();
}
