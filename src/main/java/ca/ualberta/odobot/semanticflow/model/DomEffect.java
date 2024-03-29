package ca.ualberta.odobot.semanticflow.model;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DomEffect extends AbstractArtifact {

    private static final Logger log = LoggerFactory.getLogger(DomEffect.class);

    public enum EffectType{
        ADD, REMOVE, SHOW, HIDE
    }
    private EffectType action;
    private Element effectElement;

    private String text; // Derived from outerText

    public EffectType getAction() {
        return action;
    }

    public void setAction(EffectType action) {
        this.action = action;
    }

    public Element getEffectElement() {
        return effectElement;
    }

    public void setEffectElement(Element effectElement) {
        this.effectElement = effectElement;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Element getTargetElement(){
        if(action == EffectType.REMOVE || action == EffectType.HIDE){
            return effectElement;
        }else{
            return super.getTargetElement();
        }
    }


}
