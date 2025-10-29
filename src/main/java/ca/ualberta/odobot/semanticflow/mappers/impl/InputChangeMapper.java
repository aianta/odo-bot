package ca.ualberta.odobot.semanticflow.mappers.impl;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.model.CheckboxEvent;
import ca.ualberta.odobot.semanticflow.model.InputChange;
import ca.ualberta.odobot.semanticflow.model.RadioButtonEvent;
import ca.ualberta.odobot.semanticflow.model.TinymceEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link InputChangeMapper} contains all the logic necessary to produce a {@link ca.ualberta.odobot.semanticflow.model.InputChange} object
 * from a json object with the correct information.
 */
public class InputChangeMapper extends JsonMapper<InputChange> {
    private static final Logger log = LoggerFactory.getLogger(InputChangeMapper.class);

    private static final String XPATH_FIELD = "eventDetails_xpath";
    private static final String ELEMENT_FIELD = "eventDetails_element";
    private static final String METADATA_FIELD = "metadata";

    private static final String METADATA_VALUE_FIELD = "fieldValue";

    private static final String ELEMENT_HTML_FIELD = "outerHTML";
    private static final String ELEMENT_TAG_FIELD = "localName";
    private static final String ELEMENT_ID_FIELD = "id";
    private static final String ELEMENT_TEXT_FIELD = "outerText";
    private static final String ELEMENT_BASEURI_FIELD = "baseURI";



    @Override
    public InputChange map(JsonObject event) {

        InputChange result = null;

        //Handle input change events from tiny MCE
        if (event.containsKey("eventDetails_source") && event.getString("eventDetails_source").equals("tinyMCE")) {
            JsonObject element = new JsonObject(event.getString(ELEMENT_FIELD));

            result = new TinymceEvent();
            result.setDomSnapshot(getDOMSnapshot(event));
            result.setXpath(event.getString(XPATH_FIELD));
            ((TinymceEvent)result).setEditorId(event.getString("eventDetails_editorId"));
            ((TinymceEvent)result).setInputType(event.getString("eventDetails_inputType"));
            ((TinymceEvent)result).setValue(event.getString("eventDetails_editorContent"));

            result.setOuterHTML(element.getString(ELEMENT_HTML_FIELD));
            result.setInputElement(extractElement(element.getString(ELEMENT_HTML_FIELD)));
            result.setTag(element.getString(ELEMENT_TAG_FIELD));
            result.setBaseURI(element.getString(ELEMENT_BASEURI_FIELD));
        }else{
            JsonObject element = new JsonObject(event.getString(ELEMENT_FIELD));
            //TODO METADATA_FIELD is not valid json string, is mongo BSON
            JsonArray metadata = new JsonArray(event.getString(METADATA_FIELD));


            //Create the appropriate type of InputChange
            if(isCheckbox(element)){
                result = new CheckboxEvent();
            }else if (isRadioButton(element)) {
                result = new RadioButtonEvent();
                ((RadioButtonEvent)result).setRadioGroup(event.getString("eventDetails_radioGroup"));

                /**
                 * This will be a list of all radio buttons in the same group.
                 */
                JsonArray relatedElements = new JsonArray(event.getString("eventDetails_relatedElements"));
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

                for(RadioButtonEvent.RadioButton button:buttons){
                    ((RadioButtonEvent) result).addOption(button);
                }

            }else{
                result = new InputChange();
            }

            //Otherwise handle a regular input change event
            result.setDomSnapshot(getDOMSnapshot(event));
            result.setXpath(event.getString(XPATH_FIELD));
            result.setOuterHTML(element.getString(ELEMENT_HTML_FIELD));
            result.setInputElement(extractElement(element.getString(ELEMENT_HTML_FIELD)));
            result.setTag(element.getString(ELEMENT_TAG_FIELD));
            result.setHtmlId(element.getString(ELEMENT_ID_FIELD));
            result.setBaseURI(element.getString(ELEMENT_BASEURI_FIELD));

            if(result.getInputElement().hasAttr("placeholder")){
                result.setPlaceholderText(result.getInputElement().attr("placeholder"));
            }
            //TODO - this will yield results 1 character off, this is a problem in the data being sent back by LogUI
            result.setValue(getMetadataValue(METADATA_VALUE_FIELD, metadata));
        }

        return result;
    }

    private static boolean isRadioButton(JsonObject elementData){
        Element inputElement = Jsoup.parse(elementData.getString(ELEMENT_HTML_FIELD)).body().firstElementChild();

        if(inputElement.hasAttr("type")){
            return inputElement.attr("type").equals("radio");
        }else{
            return false;
        }

    }

    private static boolean isCheckbox(JsonObject elementData){
        Element inputElement = Jsoup.parse(elementData.getString(ELEMENT_HTML_FIELD)).body().firstElementChild();

        if(inputElement.attributes().hasKey("type")){
            return inputElement.attributes().get("type").equals("checkbox");
        }else{
            return false;
        }
    }

    public static String getMetadataValue(String key, JsonArray metadata){
        Optional<JsonObject> meta = metadata.stream()
                .map(o->(JsonObject)o)
                .filter(json->json.getString("name").equals(key))
                .findFirst();


        return meta.isPresent()?meta.get().getString("value"):"null";
    }


}
