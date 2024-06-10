package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.ClickEvent;
import ca.ualberta.odobot.semanticflow.model.InteractionType;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogUIClickEventMapper extends JsonMapper<ClickEvent> {

    private static final Logger log = LoggerFactory.getLogger(LogUIClickEventMapper.class);


    @Override
    public ClickEvent map(JsonObject event) {

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject elementData = new JsonObject(eventDetails.getString("element"));
        JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));


        ClickEvent result = new ClickEvent();
        result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
        result.setXpath(elementData.getString("xpath"));
        result.setTag(elementData.getString("localName"));
        result.setBaseURI(elementData.getString("baseURI"));
        result.setHtmlId(elementData.getString("id"));
        result.setTriggerElement(result.getDomSnapshot().selectXpath(result.getXpath()).first());
        result.setType(InteractionType.CLICK);

        return result;
    }
}
