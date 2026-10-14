package ca.ualberta.odobot.semanticflow.model;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


/**
 * The nodes one DOM change added, removed, showed or hid. OdoX reports the changes of one DOM mutation callback as one
 * event per action, so a DomEffect can cover several nodes.
 *
 * <p>The single-node fields ({@link #getXpath()}, tag, id, base URI, {@link #getText()}, {@link #getEffectElement()})
 * describe the primary node: the most relevant node of the change, see
 * {@link ca.ualberta.odobot.semanticflow.mappers.impl.LogUIDomEffectMapper}. {@link #getXpaths()} and
 * {@link #getEffectElements()} list every node, primary first.</p>
 */
public class DomEffect extends AbstractArtifact {

    private static final Logger log = LoggerFactory.getLogger(DomEffect.class);

    public enum EffectType{
        ADD, REMOVE, SHOW, HIDE
    }
    private EffectType action;
    private Element effectElement;

    private String text; // Derived from outerText

    /**
     * The xpath of every node of the change, primary first. Null for a DomEffect built for a single node.
     */
    private List<String> xpaths;

    /**
     * The parsed element of every node of the change, primary first, when elements are parsed. Null otherwise.
     */
    private List<Element> effectElements;

    public EffectType getAction() {
        return action;
    }

    public void setAction(EffectType action) {
        this.action = action;
    }

    /**
     * @return the primary node's element.
     */
    public Element getEffectElement() {
        return effectElement;
    }

    public void setEffectElement(Element effectElement) {
        this.effectElement = effectElement;
    }

    /**
     * @return the parsed elements of every node of the change, primary first; just the primary's when only it was set,
     * and empty when elements were not parsed (live runs).
     */
    public List<Element> getEffectElements() {
        if(effectElements != null){
            return effectElements;
        }
        return effectElement == null? List.of() : List.of(effectElement);
    }

    public void setEffectElements(List<Element> effectElements) {
        this.effectElements = effectElements;
    }

    /**
     * @return the xpaths of every node of the change, primary first.
     */
    public List<String> getXpaths() {
        if(xpaths != null){
            return xpaths;
        }
        return getXpath() == null? List.of() : List.of(getXpath());
    }

    public void setXpaths(List<String> xpaths) {
        this.xpaths = xpaths;
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
