package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.InteractionType;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.Element;

/**
 * {@link ClickEventMapper} contains all the logic necessary to produce a {@link ca.ualberta.odobot.semanticflow.model.ClickEvent}
 * from a json object with the correct information.
 */
public class ClickEventMapper extends JsonMapper<ClickEvent> {

    private static final Logger log = LoggerFactory.getLogger(ClickEventMapper.class);

    private static final String ELEMENT_FIELD = "eventDetails_element";

    private static final String ELEMENT_TAG_FIELD = "localName";
    private static final String ELEMENT_XPATH_FIELD = "xpath";
    private static final String ELEMENT_BASEURI_FIELD = "baseURI";
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
        result.setType(InteractionType.CLICK);

        return result;
    }
}
///div/div/div[3]/div[1]/button[2]