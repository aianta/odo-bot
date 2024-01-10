package ca.ualberta.odobot;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NGramMatching {

    private static final Logger log = LoggerFactory.getLogger(NGramMatching.class);

    static StanfordCoreNLP pipeline;

    @BeforeAll
    public static void setup(){
        Properties properties = new Properties();
        properties.setProperty("englishWords.wordnetHome", "C:\\Program Files (x86)\\WordNet\\2.1");
        properties.setProperty("customAnnotatorClass.englishWords", "ca.ualberta.odobot.semanticflow.extraction.terms.annotators.EnglishWordAnnotator");
        properties.setProperty("annotators", "tokenize,ssplit,pos,lemma,englishWords");

        pipeline = new StanfordCoreNLP(properties);

    }

    static List<Double> expectedSupport = List.of(
            1.0,
            2.0,
            2.0,
            1.0,
            1.0,
            2.0,
            3.0,
            2.0,
            0.0,
            2.0
    );

    static List<String> targetsList = List.of(
            "assignment question bank",
            "assignment assignment",
            "assignment question assignment",
            "assignment question bank",
            "assignment question bank",
            "assignment question bank assignment question bank",
            "assignment question bank assignment question bank assignment",
            "the cow walked over the moon and then the cow walked back",
            "assignment question bank",
            "the cow walk over the moon and then the cow walk back"
    );
    static List<List<String>> ngramsList = List.of(
      List.of("assignment"),
      List.of("assignment"),
      List.of("assignment"),
      List.of("assignment", "question", "bank"),
      List.of("assignment", "question"),
      List.of("assignment", "question"),
      List.of("assignment"),
      List.of("the", "cow"),
      List.of("module"),
      List.of("walk")
    );

    @Test
    public void test(){

        Iterator<List<String>> it1 = ngramsList.iterator();
        Iterator<String> it2 = targetsList.iterator();
        Iterator<Double> it3 = expectedSupport.iterator();

        int index = 0;
        while (it1.hasNext() && it2.hasNext() && it3.hasNext()){
            String target = it2.next();
            List<String> ngram = it1.next();
            double expectedSupport = it3.next();
            index++;
            log.info("test {}", index);

            double support = getSupport2(ngram, target);
            log.info("ngram: {} target: {} expected: {} actual: {}", ngram, target, expectedSupport, support);
            assertEquals(expectedSupport, support);
        }
    }


    private static double getSupport2(List<String> ngram, String target){

        CoreDocument document = new CoreDocument(target);
        pipeline.annotate(document);


        List<CoreLabel> targetLabels = document.annotation().get(CoreAnnotations.TokensAnnotation.class);
        Iterator<CoreLabel> targetIterator = targetLabels.iterator();
        Iterator<String> ngramIterator = ngram.iterator();

        int numMatches = 0;
        boolean matchedLast = false;
        while (targetIterator.hasNext()){
            CoreLabel reference = targetIterator.next();

            if(ngramIterator.hasNext()){
                String sample = ngramIterator.next();

                if(sample.equals(reference.lemma().toLowerCase())){
                    matchedLast = true;
                }else{

                    matchedLast = false;
                    ngramIterator = ngram.iterator(); //Reset the n-gram iterator after a mismatch
                }

            }else if(matchedLast){
                matchedLast = false;
                numMatches++;
                ngramIterator = ngram.iterator(); //Reset the n-gram iterator after a match.

                if(ngramIterator.next().equals(reference.lemma().toLowerCase())){
                    matchedLast = true;
                }else{
                    ngramIterator = ngram.iterator();
                }

            }
        }

        if(matchedLast){
            numMatches++;
        }

        return (double) numMatches;

    }
}
