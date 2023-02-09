package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link InputChangeMapper} contains all the logic necessary to produce a {@link ca.ualberta.odobot.semanticflow.model.InputChange} object
 * from a json object with the correct information.
 */
public class InputChangeMapper extends JsonMapper<InputChange> {
    private static final Logger log = LoggerFactory.getLogger(InputChangeMapper.class);

    private static final String XPATH_FIELD = "eventDetails_xpath";
    private static final String ELEMENT_FIELD = "eventDetails_element";

    private static final String ELEMENT_HTML_FIELD = "outerHTML";
    private static final String ELEMENT_TAG_FIELD = "localName";
    private static final String ELEMENT_ID_FIELD = "id";
    private static final String ELEMENT_TEXT_FIELD = "outerText";
    private static final String ELEMENT_BASEURI_FIELD = "baseURI";


    @Override
    public InputChange map(JsonObject event) {

        JsonObject element = new JsonObject(event.getString(ELEMENT_FIELD));

        InputChange result = new InputChange();
        result.setDomSnapshot(getDOMSnapshot(event));
        result.setXpath(event.getString(XPATH_FIELD));
        result.setInputElement(extractElement(element.getString(ELEMENT_HTML_FIELD)));
        result.setTag(element.getString(ELEMENT_TAG_FIELD));
        result.setHtmlId(element.getString(ELEMENT_ID_FIELD));
        result.setBaseURI(element.getString(ELEMENT_BASEURI_FIELD));
        result.setPlaceholderText(result.getInputElement().attr("placeholder"));
        result.setValue(result.getInputElement().attr("value"));

        return result;
    }


}
