package ca.ualberta.odobot.semanticflow.model;


import ca.ualberta.odobot.semanticflow.extraction.terms.SourceFunctions;
import ca.ualberta.odobot.semanticflow.extraction.terms.impl.BasicStanfordNLPStrategy;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.DistanceToTarget;
import ca.ualberta.odobot.semanticflow.ranking.terms.impl.NoRanking;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ClickEvent extends AbstractArtifact implements TimelineEntity {

   private static final Logger log = LoggerFactory.getLogger(ClickEvent.class);


   //This is the actual element that triggered the event
   private Element triggerElement;
   private int elementHeight; //offsetHeight https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/offsetHeight
   private int elementWidth;  //offsetWidth  https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/offsetWidth
   // TODO - the actual screen position of the border might be interesting. However we'd have to normalize it for the display resolution
   private Document minimumDomTree; //A pruned DOM coning strictly the parents that contain the trigger element and their parents, leading up to the root.
    private InteractionType type;


    public JsonObject toJson(){

        return new JsonObject()
                .put("xpath", getXpath());
    }

    @Override
    public long timestamp() {
        return getTimestamp().toInstant().toEpochMilli();
    }

    public String symbol(){
        return "CE";
    }

    @Deprecated
    public List<String> terms() {
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        DistanceToTarget rankingStrategy = new DistanceToTarget();
        rankingStrategy.setMatchingFunction(DistanceToTarget.MatchingFunction.OWN_TEXT.getFunction());

        return rankingStrategy.getTerms(this, strategy, DistanceToTarget.SourceFunction.TEXT.getFunction());
    }

    @Deprecated
    public List<String> cssClassTerms(){
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(this, strategy, SourceFunctions.TARGET_ELEMENT_CSS_CLASSES.getFunction());
    }

    @Deprecated
    public List<String> idTerms(){
        BasicStanfordNLPStrategy strategy = new BasicStanfordNLPStrategy();
        strategy.allowDuplicates(false);
        return new NoRanking().getTerms(this, strategy, SourceFunctions.TARGET_ELEMENT_ID.getFunction());
    }

    public InteractionType getType() {
        return type;
    }

    public void setType(InteractionType type) {
        this.type = type;
    }

    public Element getTriggerElement() {
        return triggerElement;
    }

    public void setTriggerElement(Element triggerElement) {
        this.triggerElement = triggerElement;
    }

    public int getElementHeight() {
        return elementHeight;
    }

    public void setElementHeight(int elementHeight) {
        this.elementHeight = elementHeight;
    }

    public int getElementWidth() {
        return elementWidth;
    }

    public void setElementWidth(int elementWidth) {
        this.elementWidth = elementWidth;
    }

    public Document getMinimumDomTree() {
        return minimumDomTree;
    }

    public void setMinimumDomTree(Document minimumDomTree) {
        this.minimumDomTree = minimumDomTree;
    }

    private Document computeMinimumDomTree(){
       if(getDomSnapshot() == null){
           throw new NullPointerException("Cannot compute minimum dom tree because DOMSnapshot is null.");
       }
       if(getXpath() == null || getXpath().isEmpty()){
           throw new NullPointerException("Cannot compute minimum dom tree because Xpath is either null or an empty string.");
       }

       //Create a copy of the full DOMSnapshot
       Document result = getDomSnapshot().clone();

       //Find the trigger element in the copy
       Element triggerElementInClone = result.selectXpath(getXpath()).first();

       //Prune all other elements except the trigger element and parents leading to root
       prune(triggerElementInClone);

       return triggerElementInClone.ownerDocument();
   }

    /**
     * Used by {@link #computeMinimumDomTree()} to clean the DOM tree and compute {@link #minimumDomTree}.
     * @param element
     */
   private void prune(Element element){
       element.siblingElements().forEach(Element::remove);
       if(element.hasParent()){
           prune(element.parent());
       }
   }

    /**
     * This is a singular event, and so it's size on the timeline is 1.
     * @return 1
     */
   public int size(){
       return 1;
   }


}
