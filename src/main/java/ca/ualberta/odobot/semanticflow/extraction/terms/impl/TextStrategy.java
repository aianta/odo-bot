package ca.ualberta.odobot.semanticflow.extraction.terms.impl;

import ca.ualberta.odobot.semanticflow.extraction.terms.AbstractTermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import edu.stanford.nlp.ling.CoreLabel;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class TextStrategy extends AbstractTermExtractionStrategy implements TermExtractionStrategy {
    private static final Logger log = LoggerFactory.getLogger(TextStrategy.class);

    /**
     * If a term appears more than once, do we return multiple CoreLabels?
     */
    private boolean allowDuplicates = true;

    public void allowDuplicates(boolean allowDuplicates) {
        this.allowDuplicates = allowDuplicates;
    }

    @Override
    public List<CoreLabel> extractTerms(Element element) {

        //log.info("Extracting terms from: {}", element);
        String content = element.text();
        List<CoreLabel> result = tokenize(content);
        if(allowDuplicates){
            return result;
        }else{
            Set<String> words = new HashSet<>();
            Iterator<CoreLabel> it = result.iterator();
            while (it.hasNext()){
                CoreLabel curr = it.next();
                if(words.contains(curr.word())){
                    it.remove();
                }else{
                    words.add(curr.word());
                }
            }

            return result;
        }
    }
}
