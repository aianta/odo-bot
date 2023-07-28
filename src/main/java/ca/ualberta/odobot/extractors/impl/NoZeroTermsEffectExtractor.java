package ca.ualberta.odobot.extractors.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.Timeline;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Like {@link SimpleEffectTermsExtractor} but doesn't return 0 terms if nothing was made visible during the effect. Returns a single empty string term instead.
 */
public class NoZeroTermsEffectExtractor implements SemanticArtifactExtractor<Effect> {
    @Override
    public String artifactName() {
        return "terms";
    }

    @Override
    public JsonArray extract(Effect entity, int index, Timeline timeline) {
        TermRankingStrategy rankingStrategy = new NoRanking();
        BasicStanfordNLPStrategy textStrategy = new BasicStanfordNLPStrategy();
        List<String> allTerms = new ArrayList<>();

        Iterator<DomEffect> it = entity.domEffectMadeVisible().iterator();
        while (it.hasNext()){
            DomEffect curr = it.next();
            List<String> terms = rankingStrategy.getTerms(curr, textStrategy, SourceFunctions.TARGET_ELEMENT_TEXT.getFunction());
            allTerms.addAll(terms == null?List.of():terms);
        }
        //Add empty string term if no terms were extracted.
        if(allTerms.size() == 0){
            allTerms.add("");
        }
        return allTerms.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    }
}
