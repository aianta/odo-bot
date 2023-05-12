package ca.ualberta.odobot.semanticflow.model;

import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic artifact that arises from a series of input changes.
 *
 * We use the data entry class to assemble a meaningful view of a set of input changes. For example,
 * an INPUT_CHANGE event is triggered every time the user enters a character into a text field.
 * This class allows us to work with the final value of entered data.
 */
public class DataEntry extends ArrayList<InputChange> implements TimelineEntity {

    private static final Logger log = LoggerFactory.getLogger(DataEntry.class);

    private JsonObject semanticArtifacts = new JsonObject();


    /**
     * Only allow additions of input changes for the same kind of element.
     * @param ic
     * @return
     */
    public boolean add(InputChange ic){
        if(isEmpty()){
            return super.add(ic);
        }
        if(ic.getXpath().equals(xpath())){
            return super.add(ic);
        }
        log.warn("Cannot add InputChange: {} to this DataEntry because input elements do not have matching xpaths!", ic.toString());
        return false;
    }

    public String xpath(){
        return isEmpty()?null:get(size()-1).getXpath();
    }

    /**
     * @return the input element in which data is being entered.
     */
    public Element inputElement(){
        return isEmpty()?null:get(size()-1).getInputElement();
    }

    public InputChange lastChange(){
        return isEmpty()?null:get(size()-1);
    }

    public String symbol(){
        return "DE";
    }

    @Deprecated
    public List<String> terms() {
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        DistanceToTarget rankingStrategy = new DistanceToTarget();
        rankingStrategy.setMatchingFunction(DistanceToTarget.MatchingFunction.OWN_TEXT.getFunction());
        return rankingStrategy.getTerms(lastChange(), strategy, DistanceToTarget.SourceFunction.TEXT.getFunction());

    }

    @Deprecated
    public List<String> cssClassTerms(){
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(lastChange(), strategy, SourceFunctions.TARGET_ELEMENT_CSS_CLASSES.getFunction());
    }

    @Deprecated
    public List<String> idTerms(){
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(lastChange(), strategy, SourceFunctions.TARGET_ELEMENT_ID.getFunction());
    }

    public String getEnteredData(){
        return get(size()-1).getValue();
    }

    public JsonObject toJson(){
        JsonObject result = new JsonObject();
        result.put("enteredData", getEnteredData());
        return result;
    }

    @Override
    public long timestamp() {
        return lastChange().getTimestamp().toInstant().toEpochMilli();
    }

    @Override
    public JsonObject getSemanticArtifacts() {
        return this.semanticArtifacts;
    }


}
