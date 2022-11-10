package ca.ualberta.odobot.semanticflow;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.TreeModel;
import org.eclipse.rdf4j.model.util.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Parses event logs into flows.
 */
public class FlowParser {
    private static final Logger log = LoggerFactory.getLogger(FlowParser.class);

    private final String namespace;
    private final Model model = new TreeModel();
    private static int apiCalls=0;
    private static int effects=0;
    private static int clicks=0;

    /**
     * Idea:
     * srcDomElement -[emits]-> eventElement.
     * eventElement -[causes]-> effectElement.
     *
     * EffectElements can be API calls or DOM add/removes.
     */
    private IRI cause;
    private List<IRI> effectElements;

    public static class Builder{
        private String namespace;

        public String getNamespace() {
            return namespace;
        }

        public Builder setNamespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public FlowParser build(){
            FlowParser flowParser = new FlowParser(this);
            return flowParser;
        }
    }

    private FlowParser(Builder builder){
        this.namespace = builder.getNamespace();
    }

    public Model parse(List<JsonObject> events){
        events.stream().forEachOrdered(this::parse);
        log.info("Parsed {} clicks, {} apiCalls, and {} effects", clicks, apiCalls, effects);
        return model;
    }

    private void parse(JsonObject event){

        switch (event.getString("eventType")){
            case "customEvent":
                /**
                 * These are DOM_EFFECTS
                 */
                handleDOMEffect(event);
                break;
            case "interactionEvent":
                switch (event.getString("eventDetails_name")){
                    case "BUTTON_CLICK":
                        onButtonClick(event);
                        break;
                    case "INPUT_CHANGE":
                        break;
                    default:
                        log.warn("Unrecognized interaction event: {}", event.getString("eventDetails_name"));
                }
                break;
            case "HAR_EVENT":
                /**
                 * These are API calls
                 */
                handleAPICall(event);
                break;
            default:
                log.warn("Unrecognized event type: {}", event.getString("eventType"));
        }


    }

    private void initModel(){

    }

    private void handleAPICall(JsonObject event){
        if(cause == null){
            log.warn("Parsed API Call without cause...");
            return;
        }

        apiCalls++;

        log.info("HAR_EVENT: {}", event.encodePrettily());
        IRI apiCall = Values.iri(namespace, event.getString("sessionID")+"$api-call$"+apiCalls);
        Literal requestURL = Values.literal( event.getString("eventDetails_request_url"));
        Literal responseType = Values.literal(event.getString("eventDetails_response_content_mimeType"));
        Literal callTime = Values.literal(event.getString("timestamps_eventTimestamp"));

        model.add(apiCall, Values.iri(namespace, "requestUrl"), requestURL);
        model.add(apiCall, Values.iri(namespace, "responseType"), responseType);
        model.add(apiCall, Values.iri(namespace, "timestamp"), callTime);

        model.add(cause, Values.iri(namespace, "triggers"), apiCall);
    }

    private void handleDOMEffect(JsonObject event){
        if(cause == null){ //Cannot have effects without cause.
            log.warn("Parsed effect without cause....");
            return;
        }



        JsonObject underlying = horrificTrash(event);
        log.info("Result of horrific trash: {}", event.encodePrettily());
        JsonArray nodes = underlying.getJsonObject("eventDetails")
                .getJsonArray("nodes");

        if(nodes.size() == 0){
            log.warn("Got effect with 0 nodes... this probably shouldn't happen...");
            return;
        }
        JsonObject node = nodes
                .getJsonObject(0);

        effects++;

        IRI effect = Values.iri(namespace, event.getString("sessionID")+"$dom-effect$"+effects);
        Literal effectTime = Values.literal(event.getString("timestamps_eventTimestamp"));
        //TODO-> think about how/if to include/use xpath here.
        IRI domElement = Values.iri(namespace,
                        node.getString("xpath").replaceAll("[\\[\\]]","/") + "$" + node.getString("nodeName")
        );
        Literal domElementXpath = Values.literal(node.getString("xpath"));
        Literal domElementBaseURI = Values.literal(node.getString("baseURI"));
        Literal domElementOuterHTML = Values.literal( node.getString("outerHTML"));


        model.add(effect, Values.iri(namespace, event.getString("eventDetails_action")), domElement);
        model.add(effect, Values.iri(namespace, "timestamp"), effectTime);
        model.add(domElement, Values.iri(namespace, "xpath"), domElementXpath);
        model.add(domElement, Values.iri(namespace, "baseURI"), domElementBaseURI);
        //TODO -> re-add this when not visualizing
        //model.add(domElement, Values.iri(namespace, "outerHTML"), domElementOuterHTML);

        model.add(cause, Values.iri(namespace, "triggers"), effect);
    }

    private void onButtonClick(JsonObject event){
        clicks++;
        //Get underlying entry
        JsonObject underlying = horrificTrash(event);

        //Unpack metadata
        JsonArray metadata = underlying.getJsonArray("metadata");

        IRI buttonClick = Values.iri(namespace, event.getString("sessionID")+"$click$"+clicks);
        Literal buttonText = Values.literal(getMetadataValue("buttonText", metadata));
        IRI clickTime = Values.iri(namespace, event.getString("timestamps_eventTimestamp"));

        model.add(buttonClick, Values.iri(namespace, "buttonText"), buttonText);
        model.add(buttonClick, Values.iri(namespace, "timestamp"), clickTime);

        cause = buttonClick;
    }



    private String getMetadataValue(String key, JsonArray metadata){
        //TODO - handle case where not found
        return metadata.stream()
                .map(o->(JsonObject)o)
                .filter(json->json.getString("name").equals(key))
                .findFirst().get().getString("value");
    }

    private JsonObject getUnderlying(JsonObject event){
        //Get underlying entry
        return new JsonObject(event.getString("log_entry"));
    }

    /**
     * A temporary function that does bad string things to retrieve some items
     * from the log_entry field.
     *
     * TODO-This needs to be repalced with a valid json string in log_entry
     * @param event
     * @return
     */
    private JsonObject horrificTrash(JsonObject event){
        String logEntryText = event.getString("log_entry");
        logEntryText = logEntryText.substring(52);

//        log.info("trimmed:");
//        log.info("{}", logEntryText);
        logEntryText = "{" + logEntryText;

        logEntryText = logEntryText.replaceAll("=>", ":");
        logEntryText = logEntryText.replaceAll("nil", "\"null\"");
        logEntryText = logEntryText.replaceAll("\\\\#", "");
        log.info("json?");
        log.info("{}", logEntryText);

        return new JsonObject(logEntryText);
    }
}
