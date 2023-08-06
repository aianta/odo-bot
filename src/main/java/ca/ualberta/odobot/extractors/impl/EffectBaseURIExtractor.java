package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class EffectBaseURIExtractor implements SemanticArtifactExtractor<Effect> {

    public static final Logger log = LoggerFactory.getLogger(EffectBaseURIExtractor.class);

    @Override
    public String artifactName() {
        return "baseURI";
    }

    @Override
    public Object extract(Effect entity, int index, Timeline timeline) {
        Set<String> baseURIs = entity.getBaseURIs();
        if(baseURIs.size() > 1){
            log.info("More than one base URI found for effect: " + timeline.getId().toString() + "#"+ index);
            baseURIs.forEach(baseURI -> log.info("Base URI: " + baseURI));

        }
        return baseURIs.iterator().next();
    }
}
