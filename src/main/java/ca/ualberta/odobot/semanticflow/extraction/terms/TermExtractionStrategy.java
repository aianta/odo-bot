package ca.ualberta.odobot.semanticflow.extraction.terms;


import org.jsoup.nodes.Element;

import java.util.List;

/**
 * Strategy interface for extracting a bag of strings from a JSoup Element
 */
public interface TermExtractionStrategy {

    List<String> extractTerms(Element element);

}
