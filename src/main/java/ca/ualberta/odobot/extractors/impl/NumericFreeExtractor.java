package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;

import java.util.regex.Pattern;

/**
 * An artifact extractor that wraps around an existing extractor and removes any
 * numeric characters in the extracted String. Wrapped extractor must return a String.
 */
public class NumericFreeExtractor implements SemanticArtifactExtractor {


    private SemanticArtifactExtractor extractor;

    public NumericFreeExtractor( SemanticArtifactExtractor extractor){
        this.extractor = extractor;
    }

    @Override
    public String artifactName() {
        return extractor.artifactName();
    }

    @Override
    public Object extract(TimelineEntity entity, int index, Timeline timeline) {
        String result = (String)extractor.extract(entity, index, timeline);
        return  result.replaceAll("[0-9]+", "*");
    }
}
