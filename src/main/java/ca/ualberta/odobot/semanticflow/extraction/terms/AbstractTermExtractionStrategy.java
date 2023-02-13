package ca.ualberta.odobot.semanticflow.extraction.terms;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public abstract class AbstractTermExtractionStrategy {
    private static final Logger log = LoggerFactory.getLogger(AbstractTermExtractionStrategy.class);

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

}
