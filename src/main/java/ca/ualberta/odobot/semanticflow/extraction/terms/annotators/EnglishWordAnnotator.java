package ca.ualberta.odobot.semanticflow.extraction.terms.annotators;

import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.RAMDictionary;
import edu.mit.jwi.data.ILoadPolicy;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.POS;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.util.ArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

public class EnglishWordAnnotator implements Annotator, CoreAnnotation<Boolean> {


    //https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html

    private static final Logger log = LoggerFactory.getLogger(EnglishWordAnnotator.class);

    public static Set<String> NOUN = Set.of("NN", "NNS", "NNP", "NNPS");
    public static Set<String> ADJECTIVE = Set.of("JJ","JJR","JJS");
    public static Set<String> ADVERB = Set.of("RB", "RBR", "RBS");
    public static Set<String> VERB = Set.of("VB","VBD","VBG", "VBN", "VBP", "VBZ");

    private IRAMDictionary dictionary = null;

    public EnglishWordAnnotator(String name, Properties properties){
        //Init and load wordnet into memory
        try{
            String wordnetHome = properties.getProperty("englishWords.wordnetHome");

            String path = wordnetHome + File.separator + "dict";
            URL url = new URL("file", null, path);

            dictionary = new RAMDictionary(url, ILoadPolicy.IMMEDIATE_LOAD);
            dictionary.open();


        }catch (IOException ioe){
            log.error("Error initializing English Word Annotator");
            log.error(ioe.getMessage(), ioe);
        }


    }


    @Override
    public void annotate(Annotation annotation) {
        for(CoreLabel token:  annotation.get(CoreAnnotations.TokensAnnotation.class)){
            token.set(EnglishWordAnnotator.class, Boolean.FALSE); //Everything is not an english word until proven otherwise.

            POS wordnetPartOfSpeech = getPOS(token.tag());
            if(wordnetPartOfSpeech == null){
                continue; //If this token's tag doesn't match any of the ones we can use to look it up in the dictionary, we're done with this token.
            }
            //Otherwise, lookup the word
            IIndexWord idxWord = dictionary.getIndexWord(token.lemma(), getPOS(token.tag()));


            if(idxWord != null && idxWord.getTagSenseCount() > 0){ //If we found a word, then set the english word annotator value to true for this token.
                token.set(EnglishWordAnnotator.class, Boolean.TRUE);

            }

//            log.info("{} isEnglishWord: {}",token.word(), token.get(EnglishWordAnnotator.class) );
//            assert token.containsKey(EnglishWordAnnotator.class);
//            assert token.get(EnglishWordAnnotator.class) != null;
        }
    }



    private POS getPOS(String tag){
        if(NOUN.contains(tag)){
            return POS.NOUN;
        }

        if(ADJECTIVE.contains(tag)){
            return POS.ADJECTIVE;
        }

        if(ADVERB.contains(tag)){
            return POS.ADVERB;
        }

        if(VERB.contains(tag)){
            return POS.VERB;
        }

        log.error("Unrecognized part of speech tag: {}", tag);
        return null;
    }

    @Override
    public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
        return Collections.singleton(EnglishWordAnnotator.class);
    }

    @Override
    public Set<Class<? extends CoreAnnotation>> requires() {
        return Collections.unmodifiableSet(new ArraySet<>(Arrays.asList(
                CoreAnnotations.TextAnnotation.class,
                CoreAnnotations.TokensAnnotation.class,
                CoreAnnotations.LemmaAnnotation.class,
                CoreAnnotations.PartOfSpeechAnnotation.class
        )));
    }


    @Override
    public Class<Boolean> getType() {
        return Boolean.class;
    }
}
