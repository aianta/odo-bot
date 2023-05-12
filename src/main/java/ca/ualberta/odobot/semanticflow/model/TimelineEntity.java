package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.similarity.ISimilarityStrategy;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface TimelineEntity {

    int size();

    String symbol();

    JsonObject toJson();

    long timestamp();

    JsonObject getSemanticArtifacts();

}
