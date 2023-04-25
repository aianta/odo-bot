package ca.ualberta.odobot.semanticflow.statemodel;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import java.util.Set;
import java.util.UUID;

/**
 * State models are parsed from timelines.
 */
public interface StateModel {

    UUID id();

    Set<State> getStates();

    Set<TimelineEntity> getEntities();

    Set<InEdge> getInEdges();

    Set<OutEdge> getOutEdges();

}
