package ca.ualberta.odobot.semanticflow.extraction.terms.annotators;

import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.Annotator;
import edu.stanford.nlp.util.ArraySet;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Copied from: https://github.com/jconwell/coreNlp
 *
 */
public class StopWordsAnnotator implements Annotator, CoreAnnotation<Boolean> {


    @Override
    public Class<Boolean> getType() {
        return Boolean.class;
    }

    @Override
    public void annotate(Annotation annotation) {

    }

    @Override
    public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
        return Collections.singleton(StopWordsAnnotator.class);
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
}
