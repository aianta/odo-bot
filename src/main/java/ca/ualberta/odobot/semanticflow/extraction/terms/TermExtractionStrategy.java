package ca.ualberta.odobot.semanticflow.extraction.terms;


import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import edu.stanford.nlp.ling.CoreLabel;
import org.jsoup.nodes.Element;

import java.util.List;
import java.util.function.Function;

/**
 * Strategy interface for extracting a bag of strings from a JSoup Element
 */
public interface TermExtractionStrategy {

    <T extends AbstractArtifact> List<CoreLabel> extractTerms(T artifact, Function<T,String> srcFunction);

}
