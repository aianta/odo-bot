package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.impl.EffectBaseURIExtractor;
import ca.ualberta.odobot.extractors.impl.EffectExtractor;
import ca.ualberta.odobot.extractors.impl.NextIdExtractor;
import ca.ualberta.odobot.logpreprocessor.PreprocessingPipeline;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.model.Effect;
import io.vertx.rxjava3.core.Vertx;

import java.util.UUID;

public class EffectOverhaulPipeline  extends EnhancedEmbeddingPipeline implements PreprocessingPipeline {

    public EffectOverhaulPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);

        //Initialize term set extractors for added and removed terms
        EffectExtractor addedTermSetExtractor = new EffectExtractor("terms_added", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_TEXT);
        EffectExtractor removedTermSetExtractor = new EffectExtractor("terms_removed", (effect)->effect.domEffectMadeInvisible().iterator(), SourceFunctions.TARGET_ELEMENT_TEXT);
        EffectExtractor addedCssTerms = new EffectExtractor("cssClassTerms_added", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_CSS_CLASSES);
        EffectExtractor removedCssTerms = new EffectExtractor("cssClassTerms_removed", (effect)->effect.domEffectMadeInvisible().iterator(), SourceFunctions.TARGET_ELEMENT_CSS_CLASSES);
        EffectExtractor addedIdTerms = new EffectExtractor("idTerms_added", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_ID);
        EffectExtractor removedIdTerms = new EffectExtractor("idTerms_removed", (effect)->effect.domEffectMadeInvisible().iterator(), SourceFunctions.TARGET_ELEMENT_ID);
        EffectExtractor addedTagTerms = new EffectExtractor("tags_added", (effect)->effect.domEffectMadeVisible().iterator(), SourceFunctions.TARGET_ELEMENT_TAG);
        EffectExtractor removedTagTerms = new EffectExtractor("tags_removed", (effect)->effect.domEffectMadeInvisible().iterator(), SourceFunctions.TARGET_ELEMENT_TAG);

        //Clear all extractors for effects
        extractorMultimap.removeAll(Effect.class);

        //Add the new extractors to the extractor multimap
        extractorMultimap.put(Effect.class, addedTermSetExtractor);
        extractorMultimap.put(Effect.class, removedTermSetExtractor);
        extractorMultimap.put(Effect.class, addedCssTerms);
        extractorMultimap.put(Effect.class, removedCssTerms);
        extractorMultimap.put(Effect.class, addedIdTerms);
        extractorMultimap.put(Effect.class, removedIdTerms);
        extractorMultimap.put(Effect.class, new EffectBaseURIExtractor());
        extractorMultimap.put(Effect.class, new NextIdExtractor());
        extractorMultimap.put(Effect.class, addedTagTerms);
        extractorMultimap.put(Effect.class, removedTagTerms);
    }
}
