package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.extractors.impl.TemporalExtractor;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.Effect;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;

import java.util.List;
import java.util.UUID;

public class TemporalPipeline extends EnhancedEmbeddingPipeline implements PreprocessingPipeline {

    public TemporalPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);

        //Avoid recursion issues by making a copy of the super.extractorMultimap
        Multimap<Class, SemanticArtifactExtractor> _extractorMultimap = ArrayListMultimap.create();
        extractorMultimap.forEach((_class, extractor)->_extractorMultimap.put(_class, extractor));

        //Create temporal extractors for all TimelineEntity types
        TemporalExtractor<ClickEvent> clickPrevious = new TemporalExtractor<>(-1, _extractorMultimap );
        TemporalExtractor<ClickEvent> clickNext = new TemporalExtractor<>(1, _extractorMultimap);
        TemporalExtractor<Effect> effectPrevious = new TemporalExtractor<Effect>(-1, _extractorMultimap);
        TemporalExtractor<Effect> effectNext = new TemporalExtractor<>(1, _extractorMultimap);
        TemporalExtractor<DataEntry> dataEntryPrevious = new TemporalExtractor<>(-1, _extractorMultimap);
        TemporalExtractor<DataEntry> dataEntryNext = new TemporalExtractor<>(1,_extractorMultimap);

        //Add them to this pipeline's extractors.
        extractorMultimap.put(ClickEvent.class, clickPrevious);
        extractorMultimap.put(ClickEvent.class, clickNext);
        extractorMultimap.put(Effect.class, effectPrevious);
        extractorMultimap.put(Effect.class, effectNext);
        extractorMultimap.put(DataEntry.class, dataEntryPrevious);
        extractorMultimap.put(DataEntry.class, dataEntryNext);
    }

    public Future<JsonObject> makeActivityLabels(List<JsonObject> entities){
        return super.makeActivityLabels(entities);
    }

}
