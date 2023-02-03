package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.model.DomEffect;
import ca.ualberta.odobot.semanticflow.model.Effect;
import ca.ualberta.odobot.semanticflow.model.InteractionEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


public class SemanticSequencer {
    private static final Logger log = LoggerFactory.getLogger(SemanticSequencer.class);

    private Effect testEffect = new Effect();

    public void parse(List<JsonObject> events){
        events.forEach(event->parse(event));
        log.info("Effect parsed: {}", testEffect.toString());
    }

    private void parse(JsonObject event){
        //All events provide a snapshot of the dom at event time. Extract it here.
        JsonObject domSnapshot = new JsonObject(event.getString("eventDetails_domSnapshot"));
        Document parsedDOMSnapshot = Jsoup.parse(domSnapshot.getString("outerHTML"));

        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (InteractionEvent.InteractionType.getType(event.getString("eventDetails_name"))){
                    case CLICK -> {
                        JsonArray path = new JsonArray(event.getString("eventDetails_path"));
                        JsonObject triggerElementInfo = path.getJsonObject(0);

                        String tag = triggerElementInfo.getString("localName");
                        String baseUri = triggerElementInfo.getString("baseURI");
                        int offsetWidth = triggerElementInfo.getInteger("offsetWidth");
                        int offsetHeight = triggerElementInfo.getInteger("offsetHeight");
                        String htmlId = triggerElementInfo.getString("id");

                        InteractionEvent interactionEvent = new InteractionEvent();
                        interactionEvent.setXpath(triggerElementInfo.getString("xpath"));
                        interactionEvent.setDomSnapshot(parsedDOMSnapshot);
                        interactionEvent.setTag(tag);
                        interactionEvent.setBaseURI(baseUri);
                        interactionEvent.setHtmlId(htmlId);
                        interactionEvent.setTriggerElement(parsedDOMSnapshot.selectXpath(interactionEvent.getXpath()).first());
                        interactionEvent.setElementHeight(offsetHeight);
                        interactionEvent.setElementWidth(offsetWidth);
                        interactionEvent.setMinimumDomTree(interactionEvent.getMinimumDomTree());
                        interactionEvent.setType(InteractionEvent.InteractionType.CLICK);

                    }
                    case INPUT -> {}
                }
                break;
            case "customEvent":
                if(event.getString("eventDetails_name").equals("DOM_EFFECT")){
                    /*
                     * Do processing required for all DOM_EFFECTS. Like extracting their
                     * nodes and HTML values.
                     */
                    JsonArray nodes = new JsonArray(event.getString("eventDetails_nodes"));

                    JsonObject node = nodes.getJsonObject(0);
                    Document doc = Jsoup.parseBodyFragment(node.getString("outerHTML"));
                    if (doc.body().childrenSize() != 1){
                        log.warn("this dom effect somehow is adding multiple or no elements?");
                    }
                    Element effectElement = doc.body().firstElementChild();
                    String outerText = node.getString("outerText");
                    String tag = node.getString("localName");
                    String htmlId = node.getString("id");

                    //Create the dom effect semantic artifact
                    DomEffect domEffect = new DomEffect();
                    domEffect.setXpath(event.getString("eventDetails_xpath"));
                    domEffect.setEffectElement(effectElement);
                    domEffect.setTag(tag);
                    domEffect.setHtmlId(htmlId);
                    domEffect.setText(outerText);
                    domEffect.setDomSnapshot(parsedDOMSnapshot);

                    switch (event.getString("eventDetails_action")){
                        case "add":
                            domEffect.setAction(DomEffect.EffectType.ADD);
                            break;
                        case "show":
                            domEffect.setAction(DomEffect.EffectType.SHOW);
                            break;
                        case "hide":
                            domEffect.setAction(DomEffect.EffectType.HIDE);
                            break;
                        case "remove":
                            domEffect.setAction(DomEffect.EffectType.REMOVE);
                            break;
                    }

                    testEffect.add(domEffect);
                }
        }
    }
}
