package ca.ualberta.odobot.semanticflow.model;


import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class ClickEvent extends AbstractArtifact {

   private static final Logger log = LoggerFactory.getLogger(ClickEvent.class);

   public enum InteractionType{
       CLICK(Set.of("LINK_CLICK","TD_CLICK","BUTTON_CLICK_ACTUAL","BTN_CLICK")),
       INPUT(Set.of("INPUT_CHANGE"));

       InteractionType(Set<String> logNames){
           this.logNames = logNames;
       }

       public static InteractionType getType(String eventDetails_name){
           if(CLICK.logNames.contains(eventDetails_name))return CLICK;
           if(INPUT.logNames.contains(eventDetails_name))return INPUT;
           log.warn("Cannot find InteractionType for eventDetails_name: {}", eventDetails_name);
           return null;
       }

       Set<String> logNames;
   }
   //This is the actual element that triggered the event
   private Element triggerElement;
   private int elementHeight; //offsetHeight https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/offsetHeight
   private int elementWidth;  //offsetWidth  https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/offsetWidth
   // TODO - the actual screen position of the border might be interesting. However we'd have to normalize it for the display resolution
   private Document minimumDomTree; //A pruned DOM coning strictly the parents that contain the trigger element and their parents, leading up to the root.
    private InteractionType type;

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


}
