package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.DataEntry;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonArray;

public class SimpleDataEntryCssClassTermsExtractor implements SemanticArtifactExtractor<DataEntry> {
    @Override
    public String artifactName() {
        return "cssClassTerms";
    }

    @Override
    public Object extract(DataEntry entity, int index, Timeline timeline) {
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(entity.lastChange(), strategy, SourceFunctions.TARGET_ELEMENT_CSS_CLASSES.getFunction())
                .stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    }
}
