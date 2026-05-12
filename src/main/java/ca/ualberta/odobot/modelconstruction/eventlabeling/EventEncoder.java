package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.semanticflow.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Produces a string representation of a timeline/trajectory entity so that an LLM can describe what happened.
 */
public class EventEncoder {
    private static final Logger log = LoggerFactory.getLogger(EventEncoder.class);

    public static String encode(TimelineEntity entity) {

        if(entity instanceof ClickEvent clickEvent){
            return encodeClickEvent(clickEvent);
        }

        if (entity instanceof ApplicationLocationChange applicationLocationChange){
            return encodeApplicationLocationChange(applicationLocationChange);
        }

        if(entity instanceof NetworkEvent networkEvent){
            return encodeNetworkEvent(networkEvent);
        }

        if (entity instanceof Effect effect){
            return encodeDOMEffect(effect);
        }

        if(entity instanceof TinymceEvent tinymceEvent){
            return encodeTinymceEvent(tinymceEvent);
        }

        if (entity instanceof RadioButtonEvent radioButtonEvent){
            return encodeRadioButtonEvent(radioButtonEvent);
        }

        if (entity instanceof SelectEvent selectEvent){
            return encodeSelectEvent(selectEvent);
        }

        if(entity instanceof CheckboxEvent checkboxEvent){
            return encodeCheckboxEvent(checkboxEvent);
        }

        if(entity instanceof DataEntry dataEntry){
            return encodeDataEntry(dataEntry);
        }

        throw new RuntimeException("Unknown trajectory event type encountered during encoding: " + entity.getClass().getSimpleName());
    }

    private static String encodeApplicationLocationChange(ApplicationLocationChange applicationLocationChange) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Application Location Change\n");
        sb.append("The URL in the address bar of the browser has changed.\n");
        sb.append("The previous URL was: %s\n".formatted(applicationLocationChange.getFrom()));
        sb.append("The new URL is: %s\n".formatted(applicationLocationChange.getTo()));
        return sb.toString();
    }

    private static String encodeClickEvent(ClickEvent clickEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Click Event\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(clickEvent.getBaseURI().toString()));
        sb.append("Clicked HTML Element:\n%s\n".formatted(clickEvent.getTriggerElement().outerHtml()));
        return sb.toString();
    }

    private static String encodeDataEntry(DataEntry dataEntry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Data Entry\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(dataEntry.lastChange().getBaseURI().toString()));
        sb.append("Entered data:\n%s\n".formatted(dataEntry.getEnteredData()));
        sb.append("The data above was entered into the following HTML element:\n%s\n".formatted(dataEntry.inputElement().outerHtml()));
        return sb.toString();
    }

    private static String encodeNetworkEvent(NetworkEvent networkEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Network Event\n");
        sb.append("The application made a network call.");

        Optional<String> graphQL = networkEvent.getGraphQLOperationName();
        if(graphQL.isPresent()){
            sb.append("The network call was to the application's GraphQL endpoint. The GraphQL operation being executed was: %s\n".formatted(graphQL.get()));
        }else{
            sb.append("The network call was an HTTP request with the method: %s on the path: %s\n".formatted(networkEvent.getMethod(),  networkEvent.getPath()));
        }

        if(networkEvent.getRequestHeaders() != null && !networkEvent.getRequestHeaders().isEmpty()){
            sb.append("The request headers were:\n%s\n".formatted(networkEvent.getRequestHeaders().encodePrettily()));
        }

        if(networkEvent.getRequestObject() != null && !networkEvent.getRequestObject().isEmpty()){
            sb.append("The request body of the network call:\n%s\n ".formatted(networkEvent.getRequestObject().encodePrettily()));
        }

        if(networkEvent.getResponseHeaders() != null && !networkEvent.getResponseHeaders().isEmpty()){
            sb.append("The response headers received from the application server:\n%s\n".formatted(networkEvent.getResponseHeaders().encodePrettily()));
        }

        if(networkEvent.getResponseObject() != null && !networkEvent.getResponseObject().isEmpty()){
            sb.append("The response received from the application server:\n%s\n".formatted(networkEvent.getResponseObject().encodePrettily()));
        }

        return sb.toString();
    }

    private static String encodeDOMEffect(Effect effect){
        StringBuilder sb = new StringBuilder();

        sb.append("Type: DOM Effects \n");

        sb.append("The application made a series of changes to the visible DOM.\n");

        if(!effect.elementsAdded().isEmpty()){
            sb.append("The following elements were added to the DOM:\n");
            effect.elementsAdded().forEach(e->sb.append("\t* %s\n".formatted(e.outerHtml())));
        }

        if(!effect.elementsRemoved().isEmpty()){
            sb.append("The following elements were removed from the DOM:\n");
            effect.elementsRemoved().forEach(e->sb.append("\t* %s\n".formatted(e.outerHtml())));
        }

        if(!effect.elementsHidden().isEmpty()){
            sb.append("The following elements were hidden using CSS rules in the DOM:\n");
            effect.elementsHidden().forEach(e->sb.append("\t* %s\n".formatted(e.outerHtml())));
        }

        if(!effect.elementsShown().isEmpty()){
            sb.append("The following elements were shown using CSS rules in the DOM:\n");
            effect.elementsShown().forEach(e->sb.append("\t* %s\n".formatted(e.outerHtml())));
        }

        return sb.toString();
    }

    private static String encodeCheckboxEvent(CheckboxEvent checkboxEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Checkbox Event\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(checkboxEvent.getBaseURI().toString()));
        sb.append("The user interacted with a checkbox.\n");
        sb.append("The checkbox HTML element looked like this:\n%s\n".formatted(checkboxEvent.getInputElement().outerHtml()));
        sb.append("The value of the checkbox element is now: %s\n".formatted(checkboxEvent.getValue()));
        return sb.toString();
    }

    private static String encodeRadioButtonEvent(RadioButtonEvent radioButtonEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: RadioButton Event\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(radioButtonEvent.getBaseURI().toString()));
        sb.append("The user interacted with a radio button in the radio button group named %s.\n".formatted(radioButtonEvent.getRadioGroup()));
        sb.append("The following options were available in the radio button group:\n");
        radioButtonEvent.getOptions().forEach(rb->sb.append("\t* %s\n".formatted(rb.getHtml())));
        sb.append("The user selected the following radio button:\n%s\n".formatted(radioButtonEvent.getCheckedButton().getHtml()));

        return sb.toString();
    }

    private static String encodeTinymceEvent(TinymceEvent tinymceEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Tinymce Event\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(tinymceEvent.getBaseURI().toString()));
        sb.append("The user interacted with a tinymce content editor.\n");
        sb.append("The content editor's id was: %s\n".formatted(tinymceEvent.getEditorId()));
        sb.append("The user entered the following data into the editor:\n%s\n".formatted(tinymceEvent.getValue()));
        return sb.toString();
    }

    private static String encodeSelectEvent(SelectEvent selectEvent){
        StringBuilder sb = new StringBuilder();
        sb.append("Type: Select Event\n");
        sb.append("This event was observed on the following page/view of the application: %s\n".formatted(selectEvent.getBaseURI().toString()));
        sb.append("The user interacted with a select dropdown element.\n");
        sb.append("The select dropdown HTML element looked like this:\n%s\n".formatted(selectEvent.selectElement().outerHtml()));
        SelectEvent.Option selectedOption = selectEvent.getSelectedOption();
        if(selectedOption == null){
            sb.append("The user has not yet selected any option from the dropdown.\n");
        }else{
            sb.append("The user selected the option with the label '%s' and value '%s' from the dropdown.\n".formatted(selectedOption.label(), selectedOption.value()));
        }
        return sb.toString();
    }

}
