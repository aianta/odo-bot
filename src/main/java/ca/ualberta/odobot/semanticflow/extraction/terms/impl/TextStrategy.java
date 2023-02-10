package ca.ualberta.odobot.semanticflow.extraction.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.AbstractTermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.List;

public class TextStrategy extends AbstractTermExtractionStrategy implements TermExtractionStrategy {
    private static final Logger log = LoggerFactory.getLogger(TextStrategy.class);
    @Override
    public List<String> extractTerms(Element element) {

        log.info("Extracting terms from: {}", element);
        String content = element.text();

        return  tokenize(content);
    }
}
