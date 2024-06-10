package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper.getMetadataValue;

public class LogUIInputChangeMapper extends JsonMapper<InputChange> {

    private static final Logger log = LoggerFactory.getLogger(LogUIInputChangeMapper.class);

    @Override
    public InputChange map(JsonObject event) {

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject elementData = new JsonObject(eventDetails.getString("element"));
        JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));
        JsonArray metadata = event.getJsonArray("metadata");

        InputChange result = new InputChange();
        result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
        result.setXpath(eventDetails.getString("xpath"));
        result.setInputElement(extractElement(elementData.getString("outerHTML")));
        result.setTag(elementData.getString("localName"));
        result.setHtmlId(elementData.getString("id"));
        result.setBaseURI(elementData.getString("baseURI"));
        result.setPlaceholderText(result.getInputElement().attr("placeholder"));
        result.setValue(getMetadataValue("fieldValue", metadata));

        return result;
    }
}
