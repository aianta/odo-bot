package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import io.vertx.core.json.JsonObject;

import java.util.List;

public interface TimelineEntity {

    int size();

    String symbol();

    List<String> terms();

    JsonObject toJson();

}
