package ca.ualberta.odobot.semanticflow.ranking.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import edu.stanford.nlp.ling.CoreLabel;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A no-op ca.ualberta.odobot.ranking strategy, just returns whatever the extraction strategy did.
 * @author Alexandru Ianta
 */
public class NoRanking implements TermRankingStrategy<AbstractArtifact> {
    @Override
    public <T extends AbstractArtifact> List<String> getTerms(T artifact, TermExtractionStrategy extractionStrategy, Function<T, String> source) {
        return extractionStrategy.extractTerms(artifact, source)
                .stream().map(CoreLabel::word).collect(Collectors.toList());
    }
}
