package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import ca.ualberta.odobot.semanticflow.model.TinymceEvent;
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

        InputChange result = null;
        if (eventDetails.containsKey("source") && eventDetails.getString("source").equals("tinyMCE")){
            result = new TinymceEvent();
            ((TinymceEvent)result).setInputType(eventDetails.getString("inputType"));
            ((TinymceEvent)result).setEditorId(eventDetails.getString("editorId"));
            ((TinymceEvent)result).setValue(eventDetails.getString("editorContent"));

            result.setInputElement(extractElement(elementData.getString("outerHTML")));
        }else{
            result = new InputChange();
            result.setInputElement(extractElement(elementData.getString("outerHTML"))); //Input element needs to be set before placeholder
            result.setHtmlId(elementData.getString("id"));
            result.setPlaceholderText(result.getInputElement().attr("placeholder"));
            result.setValue(getMetadataValue("fieldValue", metadata));
        }

        result.setDomSnapshot(Jsoup.parse(domData.getString("outerHTML")));
        result.setXpath(eventDetails.getString("xpath"));

        result.setTag(elementData.getString("localName"));
        result.setBaseURI(elementData.getString("baseURI"));



        return result;
    }
}
