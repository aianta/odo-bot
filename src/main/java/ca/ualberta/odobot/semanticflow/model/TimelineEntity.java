package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;

import java.util.List;

public interface TimelineEntity {

    int size();

    String symbol();

    List<String> terms(TermRankingStrategy rankingStrategy, TermExtractionStrategy extractionStrategy);

}
