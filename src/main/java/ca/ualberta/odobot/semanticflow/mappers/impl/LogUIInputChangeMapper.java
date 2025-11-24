package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.CheckboxEvent;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import ca.ualberta.odobot.semanticflow.model.RadioButtonEvent;
import ca.ualberta.odobot.semanticflow.model.TinymceEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper.getMetadataValue;

public class LogUIInputChangeMapper extends JsonMapper<InputChange> {

    private static final Logger log = LoggerFactory.getLogger(LogUIInputChangeMapper.class);

    @Override
    public InputChange map(JsonObject event) {

        JsonObject eventDetails = event.getJsonObject("eventDetails");
        JsonObject elementData = new JsonObject(eventDetails.getString("element"));
        JsonObject domData = new JsonObject(eventDetails.getString("domSnapshot"));
        JsonArray metadata = event.getJsonArray("metadata");

        Element element = extractElement(elementData.getString("outerHTML"));

        /**
         * Handle the different kinds of input change events:
         * TinyMCE, Checkbox, RadioButton, or regular input changes (text).
         */
        InputChange result = null;
        if (eventDetails.containsKey("source") && eventDetails.getString("source").equals("tinyMCE")){
            result = new TinymceEvent();
            ((TinymceEvent)result).setInputType(eventDetails.getString("inputType"));
            ((TinymceEvent)result).setEditorId(eventDetails.getString("editorId"));
            ((TinymceEvent)result).setValue(eventDetails.getString("editorContent"));

            result.setInputElement(element);

        }else if (isCheckbox(element)){
            result = new CheckboxEvent();

        }else if (isRadioButton(element)){
            result = new RadioButtonEvent();
            ((RadioButtonEvent) result).setRadioGroup(eventDetails.getString("radioGroup"));

            //Extract the other radio buttons in the radio group.
            JsonArray relatedElements = new JsonArray(eventDetails.getString("relatedElements"));
            List<RadioButtonEvent.RadioButton> buttons = relatedElements.stream().map(o->(JsonObject)o)
                    .map(_element->{
                        RadioButtonEvent.RadioButton radioButton = new RadioButtonEvent.RadioButton(
                                _element.getString("xpath"),
                                _element.getString("html"),
                                _element.getBoolean("checked"),
                                _element.getString("value")
                        );
                        return radioButton;
                    }).toList();
            for (RadioButtonEvent.RadioButton radioButton : buttons){
                ((RadioButtonEvent) result).addOption(radioButton);
            }
            result.setInputElement(element);

        }else {
            result = new InputChange();
            result.setInputElement(element); //Input element needs to be set before placeholder
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

    private static boolean isRadioButton(Element element){
        if(element.hasAttr("type")){
            return element.attr("type").equals("radio");
        }
        return false;
    }

    private static boolean isCheckbox(Element element){
        if (element.hasAttr("type")){
            return element.attr("type").equals("checkbox");
        }
        return false;
    }
}
