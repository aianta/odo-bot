package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import com.google.common.collect.Multimap;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Extracts semantic artifacts from neighbours of a timeline entity for use in
 * this timeline entity's representation.
 */
public class TemporalExtractor<T extends TimelineEntity> implements SemanticArtifactExtractor<T> {



    private static final Logger log = LoggerFactory.getLogger(TemporalExtractor.class);

    private Multimap<Class, SemanticArtifactExtractor> extractorMultimap = null;

    /**
     * Temporal offset:
     * -1 = previous
     *  1 = next
     */
    private int offset;
    public TemporalExtractor(int offset, Multimap<Class,SemanticArtifactExtractor> extractorMultimap){
        if (offset == 0){
            throw new RuntimeException("Cannot init TemporalExtractor with offset of 0!");
        }
        this.offset = offset;
        this.extractorMultimap = extractorMultimap;
    }

    @Override
    public String artifactName() {
        return offset < 0 ?"previous":"next";
    }

    @Override
    public Object extract(T entity, int index, Timeline timeline) {


        if((index + offset) >= timeline.size()){ //If there is no neighbor at this offset
            return new JsonObject(); //Return an empty object
        }

        if((index + offset) < 0){ //If there is no previous neighbor at this offset
            return new JsonObject(); //Return an empty object
        }


        TimelineEntity target = timeline.get(index + offset);

        Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(target.getClass());
        JsonObject shadow = new JsonObject(); // JsonObject containing the semantic artifacts of the other timeline entity
        shadow.put("symbol",target.symbol());
        entityExtractors.forEach(extractor->{
            shadow.put(extractor.artifactName(), extractor.extract(target, index + offset, timeline));
        });

        //TODO - re-enable if you figure out how to handle max token busting with roberta.
//        if(entity instanceof Effect){
//            Effect effect = (Effect) entity;
//            shadow.put("madeVisible", Effect.toJson(effect.madeVisible()));
//            shadow.put("madeInvisible", Effect.toJson(effect.madeInvisible()));
//        }


        return shadow;
    }

}
