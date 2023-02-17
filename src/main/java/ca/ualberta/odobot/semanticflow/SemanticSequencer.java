package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.DomEffectMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


public class SemanticSequencer {
    private static final Logger log = LoggerFactory.getLogger(SemanticSequencer.class);


    private DomEffectMapper domEffectMapper = new DomEffectMapper();
    private ClickEventMapper clickEventMapper = new ClickEventMapper();
    private InputChangeMapper inputChangeMapper = new InputChangeMapper();

    private Timeline line;

    public Timeline parse(List<JsonObject> events){
        line = new Timeline();
        line.setAnnotations(line.getAnnotations().put("origin-es-index", SemanticFlowParser.RDF_REPO_ID));
        events.forEach(event->parse(event));
        return line;
    }

    private void parse(JsonObject event){


        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (InteractionType.getType(event.getString("eventDetails_name"))){
                    case CLICK -> {
                        ClickEvent clickEvent = clickEventMapper.map(event);
                        line.add(clickEvent);
                        log.info("handled CLICK");

                    }
                    case INPUT -> {
                        InputChange inputChange = inputChangeMapper.map(event);

                        /**  Check if the last entity in the timeline is a {@link DataEntry},
                         * if so, add this input change to it. Otherwise, create a new
                         * DataEntry and add this input change to it before adding the created DataEntry to the timeline. */
                        if(line.last() != null && line.last() instanceof DataEntry){
                            DataEntry dataEntry = (DataEntry) line.last();
                            if(!dataEntry.add(inputChange)){
                                //If the input change was not added it is because it is referring to a different input element
                                //So create a separate DataEntry object for this input change and add it to the timeline.
                                DataEntry newEntry = new DataEntry();
                                newEntry.add(inputChange);
                                line.add(newEntry);
                            }
                        }

                        if(line.last() == null || !(line.last() instanceof DataEntry)){
                            DataEntry dataEntry = new DataEntry();
                            dataEntry.add(inputChange);
                            line.add(dataEntry);
                        }
                        log.info("handled INPUT");

                    }
                }
                break;
            case "customEvent":
                if(event.getString("eventDetails_name").equals("DOM_EFFECT")){
                    DomEffect domEffect = domEffectMapper.map(event);
                    if(domEffect == null) {return;} //
                    /**
                        Check if the last entity in the timeline is an {@link Effect},
                        if so, add this domEffect to it. Otherwise, create a new Effect
                        and add this domEffect to it before adding the created Effect to the timeline.
                     */
                    if(line.last() != null && line.last() instanceof Effect){
                        Effect effect = (Effect) line.last();
                        effect.add(domEffect);
                    }

                    if(line.last() == null || !(line.last() instanceof Effect)){
                        Effect effect = new Effect();
                        effect.add(domEffect);
                        line.add(effect);
                    }
                }
                log.info("handled DOM_EFFECT");
        }
    }
}
