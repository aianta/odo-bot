package ca.ualberta.odobot.semanticflow.extraction.terms;

import ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator;
import ca.ualberta.odobot.semanticflow.model.AbstractArtifact;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class AbstractTermExtractionStrategy implements TermExtractionStrategy {
    private static final Logger log = LoggerFactory.getLogger(AbstractTermExtractionStrategy.class);

    /**
     * If a term appears more than once, do we return multiple CoreLabels?
     */
    protected boolean allowDuplicates = true;

    static StanfordCoreNLP pipeline = null;

    public AbstractTermExtractionStrategy(){
        if(pipeline == null){
            Properties properties = new Properties();
            properties.setProperty("englishWords.wordnetHome", "C:\\Program Files (x86)\\WordNet\\2.1");
            properties.setProperty("customAnnotatorClass.englishWords", "ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator");
            properties.setProperty("annotators", "tokenize,ssplit,pos,lemma,englishWords");

            /**
             * For more information on options check the following links:
             * https://stanfordnlp.github.io/CoreNLP/tokenize.html
             * https://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/process/PTBTokenizer.html
             */
            properties.setProperty("tokenize.options", "splitHyphenated=true");

            pipeline = new StanfordCoreNLP(properties);
        }


    }


    public void allowDuplicates(boolean allowDuplicates) {
        this.allowDuplicates = allowDuplicates;
    }


    public List<CoreLabel> extractTerms(String input){
        if(input.isBlank()){
            return List.of();
        }
        List<CoreLabel> result = tokenize(input);
        return allowDuplicates? result:removeDuplicates(result);
    }

    public <T extends AbstractArtifact> List<String> extractTerms(T artifact, Function<T, String> sourceText, Function<CoreLabel, CoreLabel> transformFunction, Predicate<CoreLabel> filterFunction){
        List<CoreLabel> result = extractTerms(sourceText.apply(artifact));
        return result.stream().filter(filterFunction)
                .map(transformFunction)
                .map(term->term.word())
                .collect(Collectors.toList());
    }

    public <T extends AbstractArtifact> List<CoreLabel> extractTerms(T artifact, Function<T, String> sourceText){
        log.debug("targetArtifact xpath: {}", artifact.getXpath());
        return extractTerms(sourceText.apply(artifact));
    }

    protected List<CoreLabel> tokenize(String input){
        List<CoreLabel> result = new ArrayList<>();

        input = input.replaceAll("_", " "); //Replace underscores with spaces

        log.debug("Tokenizing: {}", input);

        CoreDocument document = new CoreDocument(input);
        pipeline.annotate(document);
        for(CoreLabel tok: document.annotation().get(CoreAnnotations.TokensAnnotation.class)){
            //log.info("{} {} isEnglishWord: {}",tok.word(), tok.tag(), tok.get(EnglishWordAnnotator.class));
            if(!tok.containsKey(EnglishWordAnnotator.class) || tok.get(EnglishWordAnnotator.class) == null ){
                throw new RuntimeException("Missing englishWord Annotation!");

            }

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
