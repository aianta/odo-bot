package ca.ualberta.odobot.semanticflow.ranking.terms;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;

import java.util.List;
import java.util.function.Function;

/**
 * Strategy interface for producing order lists of terms from SemanticArtifacts
 *
 * @author Alexandru Ianta
 */
public interface TermRankingStrategy<T extends AbstractArtifact> {

    <T extends AbstractArtifact> List<String> getTerms(T artifact, TermExtractionStrategy extractionStrategy, Function<T , String> source);

}
