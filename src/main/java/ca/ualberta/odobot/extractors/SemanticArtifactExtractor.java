package ca.ualberta.odobot.extractors;

import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

public interface SemanticArtifactExtractor<T extends TimelineEntity> {

    /**
     * Name used as key in {@link TimelineEntity} json representation.
     * @return
     */
    String artifactName();

    Object extract(T entity, int index, Timeline timeline);



}
