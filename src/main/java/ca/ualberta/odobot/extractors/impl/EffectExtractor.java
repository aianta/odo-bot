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
import java.util.function.Function;

/**
 * This extractor allows us to produce terms from different sets of elements in a {@link Effect} entities.
 * Additionally, we can also specify a Source function to extract different kinds of terms. IE: cssClassTerms, etc.
 */
public class EffectExtractor implements SemanticArtifactExtractor<Effect> {

    private String artifactName;
    private Function<Effect,Iterator<DomEffect>> iteratorFunction;
    private SourceFunctions sourceFunction;

    public EffectExtractor(String artifactName, Function<Effect, Iterator<DomEffect>> setIterator, SourceFunctions sourceFunction){
        this.artifactName = artifactName;
        this.iteratorFunction = setIterator;
        this.sourceFunction = sourceFunction;
    }

    @Override
    public String artifactName() {
        return artifactName;
    }

    @Override
    public Object extract(Effect entity, int index, Timeline timeline) {

        TermRankingStrategy rankingStrategy = new NoRanking();
        BasicStanfordNLPStrategy textStrategy = new BasicStanfordNLPStrategy();
        textStrategy.allowDuplicates(false);
        List<String> terms = new ArrayList<>();

        Iterator<DomEffect> it = iteratorFunction.apply(entity);
        while (it.hasNext()){
            DomEffect curr = it.next();
            List<String> _terms = rankingStrategy.getTerms(curr, textStrategy, sourceFunction.getFunction());
            terms.addAll(terms == null?List.of():_terms);
        }
        return terms.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

    }
}
