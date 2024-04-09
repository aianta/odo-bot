package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MinimalPipeline extends SimplePreprocessingPipeline{

    private static final Logger log = LoggerFactory.getLogger(MinimalPipeline.class);

    public MinimalPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);

        setId(id);
        setName(name);

        extractorMultimap.clear();

    }

    public Future<Timeline> makeTimeline(String flightName, List<JsonObject> events){
        SemanticSequencer sequencer = new SemanticSequencer();


        //Timeline data structure construction
        Timeline timeline = sequencer.parse(events);
        timeline.getAnnotations().put("flight-name", flightName);

        int originalTimelineSize = timeline.size();


        /**
         * Semantic artifact extraction:
         *
         * Go through each timeline entity and get all matching extractors for that entity's class.
         * Then apply each extractor to the entity and add it's output to the entity's semantic artifacts.
         */
        ListIterator<TimelineEntity> it = timeline.listIterator();

        while (it.hasNext()){
            try{
                TimelineEntity entity = it.next();
                if(entity.symbol().equals("NET")){
                    continue;
                }

                Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(entity.getClass());

                entityExtractors.forEach(extractor->
                        entity.getSemanticArtifacts().put(extractor.artifactName(), extractor.extract(entity,it.previousIndex(),timeline)));


            }catch (Exception e){
                log.error(e.getMessage(),e);
            }


        }


        return Future.succeededFuture(timeline);
    }
}
