package ca.ualberta.odobot.modelconstruction.eventlabeling;

import ca.ualberta.odobot.semanticflow.model.*;

import java.util.Optional;

/**
 * Produces a string representation of a timeline/trajectory entity so that an LLM can describe what happened.
 */
public class EventEncoder {

    public static String encode(TimelineEntity entity) {

        switch (entity){
            case ClickEvent clickEvent: return encodeClickEvent(clickEvent);
            case DataEntry dataEntry: return encodeDataEntry(dataEntry);
            case NetworkEvent networkEvent: return encodeNetworkEvent(networkEvent);
            case Effect domEffect: return encodeDOMEffect(domEffect);
            case CheckboxEvent checkboxEvent: return encodeCheckboxEvent(checkboxEvent);
            case RadioButtonEvent radioButtonEvent: return encodeRadioButtonEvent(radioButtonEvent);
            case TinymceEvent tinymceEvent: return encodeTinymceEvent(tinymceEvent);
            default:
                throw new IllegalStateException("Unexpected value: " + entity);
        }

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

}
