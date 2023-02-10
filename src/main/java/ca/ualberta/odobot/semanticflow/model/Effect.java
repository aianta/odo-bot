package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.extraction.terms.TermExtractionStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.TermRankingStrategy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Effect class allows us to treat a series of DomEffects as
 * a single entity.
 */
public class Effect extends ArrayList<DomEffect> implements TimelineEntity {

    private static final Logger log = LoggerFactory.getLogger(Effect.class);

    public String symbol(){
        return "E";
    }

    public Set<Element> netVisible(){
        Set<Element> netVisible  = madeVisible();
        netVisible.removeAll(madeInvisible());
        return netVisible;
    }


    public Set<DomEffect> domEffectMadeInvisible(){
        return getDomEffects(effect -> effect.getAction() == DomEffect.EffectType.REMOVE ||
                effect.getAction() == DomEffect.EffectType.HIDE);
    }

    public Set<DomEffect> domEffectMadeVisible(){
        return getDomEffects(effect -> effect.getAction() == DomEffect.EffectType.ADD ||
                effect.getAction() == DomEffect.EffectType.SHOW);
    }

    public Set<Element> madeVisible(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.ADD ||
                effect.getAction() == DomEffect.EffectType.SHOW);
    }

    public Set<Element> madeInvisible(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.REMOVE ||
                effect.getAction() == DomEffect.EffectType.HIDE);
    }

    public Set<Element> elementsAdded(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.ADD);
    }

    public Set<Element> elementsRemoved(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.REMOVE);
    }

    public Set<Element> elementsShown(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.SHOW);
    }

    public Set<Element> elementsHidden(){
        return getElementSet(effect -> effect.getAction() == DomEffect.EffectType.HIDE);
    }

    private Set<DomEffect> getDomEffects(Predicate<? super DomEffect> predicate){
        return stream()
                .filter(predicate)
                .collect(Collectors.toSet());
    }

    private Set<Element> getElementSet(Predicate<? super DomEffect> predicate){
        return stream()
                .filter(predicate)
                .map(DomEffect::getEffectElement)
                .collect(Collectors.toSet());
    }

    private static JsonArray toJson(Set<Element> elementSet){
        return elementSet.stream().map(Element::html).collect(
                JsonArray::new,
                JsonArray::add,
                JsonArray::addAll
        );
    }

    public String toString(){
        return toJson().encodePrettily();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject()
//                .put("added", toJson(elementsAdded()))
//                .put("shown", toJson(elementsShown()))
//                .put("hidden", toJson(elementsHidden()))
//                .put("removed", toJson(elementsRemoved()))
                .put("madeVisible", toJson(madeVisible()))
                .put("madeInvisible", toJson(madeInvisible()))
                .put("netVisible", toJson(netVisible()));
        return result;
    }

    @Override
    public List<String> terms(TermRankingStrategy rankingStrategy, TermExtractionStrategy extractionStrategy) {
        List<String> allTerms = new ArrayList<>();

        Iterator<DomEffect> it = domEffectMadeVisible().iterator();
        while (it.hasNext()){
            DomEffect curr = it.next();
            List<String> terms = rankingStrategy.getTerms(curr, extractionStrategy);
            allTerms.addAll(terms == null?List.of():terms);
        }

        return allTerms;
    }
}
