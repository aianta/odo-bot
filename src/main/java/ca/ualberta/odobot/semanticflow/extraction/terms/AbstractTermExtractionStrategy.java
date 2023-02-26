package ca.ualberta.odobot.semanticflow.extraction.terms;

import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

public abstract class AbstractTermExtractionStrategy implements TermExtractionStrategy {
    private static final Logger log = LoggerFactory.getLogger(AbstractTermExtractionStrategy.class);

    /**
     * If a term appears more than once, do we return multiple CoreLabels?
     */
    protected boolean allowDuplicates = true;


    public void allowDuplicates(boolean allowDuplicates) {
        this.allowDuplicates = allowDuplicates;
    }


    public List<CoreLabel> extractTerms(String input){
        List<CoreLabel> result = tokenize(input);
        return allowDuplicates? result:removeDuplicates(result);
    }

    public <T extends AbstractArtifact> List<CoreLabel> extractTerms(T artifact, Function<T, String> sourceText){
        return extractTerms(sourceText.apply(artifact));
    }

    protected List<CoreLabel> tokenize(String input){
        List<CoreLabel> result = new ArrayList<>();
        log.info("Tokenizing: {}", input);

        Properties properties = new Properties();
        properties.setProperty("annotators", "tokenize,ssplit,pos");
        /**
         * For more information on options check the following links:
         * https://stanfordnlp.github.io/CoreNLP/tokenize.html
         * https://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/process/PTBTokenizer.html
         */
        properties.setProperty("tokenize.options", "splitHyphenated=true");

        StanfordCoreNLP pipeline = new StanfordCoreNLP(properties);
        CoreDocument document = new CoreDocument(input);
        pipeline.annotate(document);
        for(CoreLabel tok: document.tokens()){
            log.debug("{} {}",tok.word(), tok.tag());
            result.add(tok);
        }

        return result;
    }


    protected List<CoreLabel> removeDuplicates(List<CoreLabel> input){
        Set<String> words = new HashSet<>();
        Iterator<CoreLabel> it = input.iterator();
        while (it.hasNext()){
            CoreLabel curr = it.next();
            if(words.contains(curr.word())){
                it.remove();
            }else{
                words.add(curr.word());
            }
        }

        return input;
    }
}
