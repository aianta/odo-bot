package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import io.vertx.core.json.JsonArray;

public class SimpleClickEventTermsExtractor implements SemanticArtifactExtractor<ClickEvent> {
    @Override
    public String artifactName() {
        return "terms";
    }

    @Override
    public JsonArray extract(ClickEvent entity) {
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        DistanceToTarget rankingStrategy = new DistanceToTarget();
        rankingStrategy.setMatchingFunction(DistanceToTarget.MatchingFunction.OWN_TEXT.getFunction());

        return rankingStrategy.getTerms(entity, strategy, DistanceToTarget.SourceFunction.TEXT.getFunction())
                .stream().collect(JsonArray::new,JsonArray::add, JsonArray::addAll);
    }
}
