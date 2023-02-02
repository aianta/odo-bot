package ca.ualberta.odobot.semanticflow.statemodel.impl;

import ca.ualberta.odobot.semanticflow.statemodel.ISemanticStructure;
import ca.ualberta.odobot.semanticflow.statemodel.ITargetContainer;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ca.ualberta.odobot.semanticflow.statemodel.ISemanticStructure} that represents a
 * user interaction with the application. Like a click for example.
 */
public class Interaction implements ISemanticStructure, ITargetContainer {
    private static final Logger log = LoggerFactory.getLogger(Interaction.class);

    Document document;
    String targetXpath;
    Element targetElement;

    public Interaction(JsonObject event){
        JsonObject domInfo = new JsonObject(event.getString("eventDetails_domSnapshot"));
        String htmlData = domInfo.getString("outerHTML");
        this.document = Jsoup.parse(htmlData);

        this.targetXpath = event.getString("eventDetails_xpath");

        this.targetElement = document.selectXpath(this.targetXpath).first();

        //Clear up the DOM.
        prune(this.targetElement);
    }

    public Document getDocument(){
        return document;
    }

    /**
     * Prunes the DOM starting from the target element and bubbling up to the root.
     * This removes all other DOM elements aside from the ones required to reach the
     * target element
     */
    private void prune(Element element){
        element.siblingElements().forEach(Element::remove);
        if(element.hasParent()){
            prune(element.parent());
        }
    }

    @Override
    public String targetXpath() {
        return targetXpath;
    }

    @Override
    public Element targetElement() {
        return targetElement;
    }
}
