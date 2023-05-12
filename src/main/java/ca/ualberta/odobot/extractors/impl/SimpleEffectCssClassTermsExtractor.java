package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonArray;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class SimpleEffectCssClassTermsExtractor implements SemanticArtifactExtractor<Effect> {
    @Override
    public String artifactName() {
        return "cssClassTerms";
    }

    @Override
    public Object extract(Effect entity) {
        TermRankingStrategy rankingStrategy = new NoRanking();
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        Set<String> allTerms = new HashSet<>();

        Iterator<DomEffect> it = entity.domEffectMadeVisible().iterator();
        while (it.hasNext()){
            DomEffect curr = it.next();
            List<String> terms = rankingStrategy.getTerms(curr, strategy, SourceFunctions.TARGET_ELEMENT_CSS_CLASSES.getFunction());
            allTerms.addAll(terms == null?List.of():terms);
        }

        return allTerms.stream().collect(JsonArray::new, JsonArray::add,JsonArray::addAll);
    }
}
