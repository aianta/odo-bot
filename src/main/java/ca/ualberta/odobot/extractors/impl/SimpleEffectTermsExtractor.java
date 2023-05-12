package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SimpleEffectTermsExtractor implements SemanticArtifactExtractor<Effect> {
    @Override
    public String artifactName() {
        return "terms";
    }

    @Override
    public JsonArray extract(Effect entity) {
        TermRankingStrategy rankingStrategy = new NoRanking();
        BasicStanfordNLPStrategy textStrategy = new BasicStanfordNLPStrategy();
        List<String> allTerms = new ArrayList<>();

        Iterator<DomEffect> it = entity.domEffectMadeVisible().iterator();
        while (it.hasNext()){
            DomEffect curr = it.next();
            List<String> terms = rankingStrategy.getTerms(curr, textStrategy, SourceFunctions.TARGET_ELEMENT_TEXT.getFunction());
            allTerms.addAll(terms == null?List.of():terms);
        }
        return allTerms.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    }
}
