package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonArray;

public class SimpleClickEventIdTermsExtractor implements SemanticArtifactExtractor<ClickEvent> {
    @Override
    public String artifactName() {
        return "idTerms";
    }

    @Override
    public JsonArray extract(ClickEvent entity, int index, Timeline timeline) {
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(entity, strategy, SourceFunctions.TARGET_ELEMENT_ID.getFunction()).stream()
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

    }
}
