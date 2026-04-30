package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.SelectEvent;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SelectEventMapper extends JsonMapper<SelectEvent> {

    private static final Logger log = LoggerFactory.getLogger(SelectEventMapper.class);

    private static final String ELEMENT_FIELD = "eventDetails_element";
    private static final String OPTIONS_FIELD = "eventDetails_options";

    private static final String ELEMENT_TAG_FIELD = "localName";
    private static final String ELEMENT_XPATH_FIELD = "xpath";
    private static final String ELEMENT_BASEURI_FIELD = "baseURI";



    @Override
    public SelectEvent map(JsonObject event) {

        log.info("{}",event.encodePrettily());

        JsonObject element = new JsonObject(event.getString(ELEMENT_FIELD));
        JsonArray options;
        try{
             options = new JsonArray(event.getString(OPTIONS_FIELD));
        }catch (DecodeException e){
            log.warn("Could not decode options for select event: {}", e.getMessage());
            options = new JsonArray();
        }


        SelectEvent selectEvent = new SelectEvent();

        selectEvent.setDomSnapshot(getDOMSnapshot(event));
        selectEvent.setXpath(element.getString(ELEMENT_XPATH_FIELD));
        selectEvent.setBaseURI(element.getString(ELEMENT_BASEURI_FIELD));
        selectEvent.setSelectElement(getDOMSnapshot(event).selectXpath(selectEvent.getXpath()).first());
        selectEvent.setTag(element.getString(ELEMENT_TAG_FIELD));

        options.stream()
                .map(JsonObject.class::cast)
                .forEach(selectEvent::addOption);

        return selectEvent;
    }
}
