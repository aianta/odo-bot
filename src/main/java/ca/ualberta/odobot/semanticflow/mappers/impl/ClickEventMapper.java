package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import io.vertx.core.json.JsonObject;

import javax.swing.text.Element;

/**
 * {@link ClickEventMapper} contains all the logic necessary to produce a {@link ca.ualberta.odobot.semanticflow.model.ClickEvent}
 * from a json object with the correct information.
 */
public class ClickEventMapper extends JsonMapper<ClickEvent> {

    private static final String ELEMENT_FIELD = "eventDetails_element";

    private static final String ELEMENT_TAG_FIELD = "localName";
    private static final String ELEMENT_XPATH_FIELD = "xpath";
    private static final String ELEMENT_BASEURI_FIELD = "baseURI";
    private static final String ELEMENT_WIDTH_FIELD = "offsetWidth";
    private static final String ELEMENT_HEIGHT_FIELD = "offsetHeight";
    private static final String ELEMENT_ID_FIELD = "id";


    @Override
    public ClickEvent map(JsonObject event) {
        JsonObject element = new JsonObject(event.getString(ELEMENT_FIELD));

        ClickEvent result = new ClickEvent();
        result.setDomSnapshot(getDOMSnapshot(event));
        result.setXpath(element.getString(ELEMENT_XPATH_FIELD));
        result.setTag(element.getString(ELEMENT_TAG_FIELD));
        result.setBaseURI(element.getString(ELEMENT_BASEURI_FIELD));
        result.setHtmlId(element.getString(ELEMENT_ID_FIELD));
        result.setTriggerElement(getDOMSnapshot(event).selectXpath(result.getXpath()).first());
        result.setElementHeight(element.getInteger(ELEMENT_HEIGHT_FIELD));
        result.setElementWidth(element.getInteger(ELEMENT_WIDTH_FIELD));
        result.setType(ClickEvent.InteractionType.CLICK);

        return result;
    }
}
