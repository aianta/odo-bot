package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.exceptions.InvalidSessionId;
import ca.ualberta.odobot.semanticflow.exceptions.InvalidTimestamp;
import ca.ualberta.odobot.semanticflow.exceptions.MissingSessionId;
import ca.ualberta.odobot.semanticflow.exceptions.MissingTimestamp;
import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.DomEffectMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;


public class SemanticSequencer {
    private static final Logger log = LoggerFactory.getLogger(SemanticSequencer.class);

    public static String TIMESTAMP_FIELD = "timestamps_eventTimestamp";
    public static String SESSION_ID_FIELD = "sessionID";
    //TODO -> I'm not sure what effect the timezone has here, this might be a thing to revist.
    //https://stackoverflow.com/questions/25612129/java-8-datetimeformatter-and-iso-instant-issues-with-zoneddatetime
    //Either way, we need the zone to be set in order to parse the timestamps.
    public static DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());


    private DomEffectMapper domEffectMapper = new DomEffectMapper();
    private ClickEventMapper clickEventMapper = new ClickEventMapper();
    private InputChangeMapper inputChangeMapper = new InputChangeMapper();

    private Timeline line;

    public Timeline parse(List<JsonObject> events, String esIndex){
        line = new Timeline();
        line.setAnnotations(line.getAnnotations().put("origin-es-index", esIndex));

        for(JsonObject event: events){
            try {
                validate(event);
                parse(event);
            } catch (MissingSessionId | InvalidSessionId | MissingTimestamp | InvalidTimestamp e) {
                log.error(e.getMessage(),e);
                log.error("Stopping parse!");
                return null;
            }
        }

        return line;
    }

    private void validate(JsonObject event) throws MissingSessionId, MissingTimestamp, InvalidTimestamp, InvalidSessionId {
        //Check that session id field exists
        if(!event.containsKey(SESSION_ID_FIELD)) throw new MissingSessionId(event);
        //Check that timestamp field exists
        if(!event.containsKey(TIMESTAMP_FIELD)) throw new MissingTimestamp(event);
        //Check validity of session Id value
        String sessionIdString = event.getString(SESSION_ID_FIELD);
        try{
            UUID.fromString(sessionIdString);
        }catch (IllegalArgumentException e){
            log.error(e.getMessage(),e);
            throw new InvalidSessionId(event);
        }
        //Check validity of timestamp value
        try{
            String dateString = event.getString(TIMESTAMP_FIELD);
            ZonedDateTime date = ZonedDateTime.parse(dateString, timeFormatter);

        }catch (DateTimeParseException e){
            log.error(e.getMessage(), e);
            throw new InvalidTimestamp(event);
        }
    }

    private void parse(JsonObject event)  {


        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (InteractionType.getType(event.getString("eventDetails_name"))){
                    case CLICK -> {
                        ClickEvent clickEvent = clickEventMapper.map(event);
                        clickEvent.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));
                        line.add(clickEvent);
                        log.info("handled CLICK");

                    }
                    case INPUT -> {
                        InputChange inputChange = inputChangeMapper.map(event);
                        inputChange.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));
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

                    if(domEffect == null) {return;} //TODO - I wonder why this was necessary
                    domEffect.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));
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
