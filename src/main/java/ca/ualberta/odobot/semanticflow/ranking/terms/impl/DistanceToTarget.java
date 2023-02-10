package ca.ualberta.odobot.semanticflow.ranking.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a list of string terms ordered by their distance to the Artifact's target element.
 *
 * @author Alexandru Ianta
 */
public class DistanceToTarget implements TermRankingStrategy<AbstractArtifact> {
    private static final Logger log = LoggerFactory.getLogger(DistanceToTarget.class);

    @Override
    public List<String> getTerms(AbstractArtifact artifact, TermExtractionStrategy extractionStrategy) {

        Map<String, Integer> termMap = new HashMap<>();

        Element targetElement = artifact.getTargetElement();
        log.info("targetElement: {}", targetElement);

        List<String> terms = extractionStrategy.extractTerms(targetElement);

        return terms;
    }
}
