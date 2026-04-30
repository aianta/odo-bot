package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.SelectEvent;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogUISelectEventMapper extends JsonMapper<SelectEvent> {

    private static final Logger log = LoggerFactory.getLogger(LogUISelectEventMapper.class);

    public SelectEvent map(JsonObject event){
        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject elementData = new JsonObject(eventDetails.getString("element"));
        JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));

        SelectEvent selectEvent = new SelectEvent();
        selectEvent.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
        selectEvent.setXpath(eventDetails.getString("xpath"));
        selectEvent.setTag(elementData.getString("localName"));
        selectEvent.setSelectElement(selectEvent.getDomSnapshot().selectXpath(selectEvent.getXpath()).first());

        return selectEvent;
    }

}
