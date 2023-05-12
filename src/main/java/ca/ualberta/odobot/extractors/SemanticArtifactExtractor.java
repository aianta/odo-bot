package ca.ualberta.odobot.extractors;

import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonArray;

public interface SemanticArtifactExtractor<T extends TimelineEntity> {

    /**
     * Name used as key in {@link TimelineEntity} json representation.
     * @return
     */
    String artifactName();

    Object extract(T entity);



}
