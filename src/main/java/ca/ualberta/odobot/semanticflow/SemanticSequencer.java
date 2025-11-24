package ca.ualberta.odobot.semanticflow;

import ca.ualberta.odobot.semanticflow.exceptions.InvalidSessionId;
import ca.ualberta.odobot.semanticflow.exceptions.InvalidTimestamp;
import ca.ualberta.odobot.semanticflow.exceptions.MissingSessionId;
import ca.ualberta.odobot.semanticflow.exceptions.MissingTimestamp;
import ca.ualberta.odobot.semanticflow.mappers.impl.ClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.DomEffectMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.InputChangeMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.NetworkEventMapper;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.sqlite.impl.DbLogEntry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static ca.ualberta.odobot.semanticflow.Utils.getNormalizedPath;


public class SemanticSequencer {
    private static final Logger log = LoggerFactory.getLogger(SemanticSequencer.class);

    public static String TIMESTAMP_FIELD = "timestamps_eventTimestamp";
    public static String SESSION_ID_FIELD = "sessionID";
    //TODO -> I'm not sure what effect the timezone has here, this might be a thing to revisit.
    //https://stackoverflow.com/questions/25612129/java-8-datetimeformatter-and-iso-instant-issues-with-zoneddatetime
    //Either way, we need the zone to be set in order to parse the timestamps.
    public static DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    //Interaction types to process from the timeline.
    public Set<InteractionType> include = Set.of(InteractionType.CLICK, InteractionType.INPUT, InteractionType.NETWORK_EVENT, InteractionType.DOM_EFFECT);

    private DomEffectMapper domEffectMapper = new DomEffectMapper();
    private ClickEventMapper clickEventMapper = new ClickEventMapper();
    private InputChangeMapper inputChangeMapper = new InputChangeMapper();
    private NetworkEventMapper networkEventMapper = new NetworkEventMapper();

    private Predicate<NetworkEvent> networkEventFilter = null;

    private Timeline line;

    /**
     * A consuming function to invoke when an artifact is mapped during sequencing
     */
    private Consumer<AbstractArtifact> artifactConsumer;


    /**
     * Allows caller to specify a consuming function to invoke whenever an {@link AbstractArtifact} is mapped as part of the sequencing process.
     * @param consumer the consuming function to invoke when an artifact is mapped during sequencing
     */
    public void setOnArtifact(Consumer<AbstractArtifact> consumer){
        this.artifactConsumer = consumer;
    }

    /**
     * Allows the caller to specify a predicate with which to test NetworkEvents captured in low-level traces. If this predicate
     * returns true, it will be included in the high-level trace (timeline) output.
     * @param networkEventFilter
     */
    public void setNetworkEventFilter(Predicate<NetworkEvent> networkEventFilter) {
        this.networkEventFilter = networkEventFilter;
    }

    public SemanticSequencer updateIncludeFilter(Set<InteractionType> include){
        this.include = include;
        return this;
    }

    public Timeline parse(List<JsonObject> events){
        line = new Timeline();

        log.info("Expected events:");
        for (int i=0; i<events.size(); i++){
            JsonObject event = events.get(i);
            log.info("[{}] {}", i, event.getString("eventDetails_name"));
        }


        for(JsonObject event: events){
            try {
                validate(event);
                parse(event);
            } catch (MissingSessionId | InvalidSessionId | MissingTimestamp | InvalidTimestamp  e) {
                log.error(e.getMessage(),e);
                log.error("Stopping parse!");
                throw new RuntimeException(e);

            }catch (Exception e){
                log.error(e.getMessage(),e);
                log.error("Stopping parse!");
                throw new RuntimeException(e);
            }
        }

        //TODO -only for training exemplar extraction
        //line.pruneNonPrecedingClickEvents();

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
//            log.error(e.getMessage(),e);
//            throw new InvalidSessionId(event);
            //TODO -> once session id are considered again properly and we don't get new ones off each page load, make this error out again.
            //For now: we'll use the timeline id
            event.put(SESSION_ID_FIELD, line.getId().toString());
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

        //If the interaction type of the event is not in our include set, ignore this event.
        if(event.containsKey("eventDetails_name") && !include.contains(InteractionType.getType(event.getString("eventDetails_name")))){
            return;
        }


        log.info("eventType: {}", event.getString("eventType"));
        log.info("eventDetails_name: {}", event.getString("eventDetails_name"));
        if(event.getString("eventDetails_name") == null){
            log.error("eventDetails_name is null, skipping event.");
            return;
        }
        switch (event.getString("eventType")){
            case "interactionEvent":
                switch (InteractionType.getType(event.getString("eventDetails_name"))){
                    case CLICK -> {
                        ClickEvent clickEvent = clickEventMapper.map(event);
                        clickEvent.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));


                        /**
                         * Special Case:
                         * If the last element in the timeline is also a click event, only add this click event if the xpaths differ.
                         * This arises from the fact that Odo-Sight can report the same click multiple times.
                         * Since we listen for click on <a> and <li> tags, if we have an element <li><a/></li> our listener will
                         * report the click twice, once for the <a> tag, and once for the <li> tag.
                         */
                         if(
                                 (line.last() instanceof ClickEvent && !((ClickEvent)line.last()).getXpath().equals(clickEvent.getXpath())) || //See special case
                                 !(line.last() instanceof ClickEvent) //Always add click events if the previous timeline entity wasn't a click event.
                        ){
                            line.add(clickEvent);


                        }


                        if(artifactConsumer != null){ //If we have an artifact consumer set
                            artifactConsumer.accept(clickEvent); //Invoke them with the newly processed artifact.
                        }


                        log.info("handled CLICK");

                    }
                    case INPUT -> {
                        InputChange inputChange = inputChangeMapper.map(event);
                        inputChange.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));

                        if(artifactConsumer != null){ //If we have an artifact consumer set
                            artifactConsumer.accept(inputChange); //Invoke them with the newly processed artifact.
                        }

                        if (inputChange instanceof RadioButtonEvent){
                            line.add((RadioButtonEvent)inputChange);
                            log.info("handled INPUT - Radio button");
                            return;
                        }

                        if(inputChange instanceof CheckboxEvent){
                            line.add((CheckboxEvent)inputChange);
                            log.info("handled INPUT - Checkbox");
                            return;
                        }

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
                switch (InteractionType.getType(event.getString("eventDetails_name"))){
                    case INPUT -> {
                        InputChange inputChange = inputChangeMapper.map(event);
                        inputChange.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));

                        if(artifactConsumer != null){
                            artifactConsumer.accept(inputChange);
                        }

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
                        log.info("handled INPUT from TinyMCE");

                    }
                    case DOM_EFFECT -> {
                        //return;
//                        TODO - We don't use DOM effects in training data, so we can skip them.


                        try{
                            DomEffect domEffect = domEffectMapper.map(event);

                            if(domEffect == null) {return;} //TODO - I wonder why this was necessary
                            domEffect.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));

                            if(artifactConsumer != null){//If we have an artifact consumer set
                                artifactConsumer.accept(domEffect);//Invoke them with the newly processed artifact.
                            }

                            /**
                             Check if the last entity in the timeline is an {@link Effect},
                             if so, add this domEffect to it. Otherwise, create a new Effect
                             and add this domEffect to it before adding the created Effect to the timeline.

                             In addition, we need to model and normalize the representation of transitions in the window location of a trace.
                             So, if the last entity in the timeline is an effect, get it's baseURI and compare it to the current
                             domEffect's baseURI. If they are equal, proceed normally, and just add the domEffect to the Effect.

                             However, if they are not equal:
                             1) Create an {@link ApplicationLocationChange} entity and populate it's {@link ApplicationLocationChange#from} and {@link ApplicationLocationChange#to}
                             fields.


                             */
                            if(line.last() != null && line.last() instanceof Effect){

                                Effect effect = (Effect) line.last();

                                if(effect.getBaseURIs().size() == 0 || effect.getBaseURIs().size() > 1){
                                    log.error("Effect baseURI set is of an invalid size! {}", effect.getBaseURIs().size());
                                    throw new RuntimeException("Invalid Effect BaseURI set size!");
                                }

                                String previousBasePath = new URL(effect.getBaseURIs().iterator().next()).getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");;
                                String currentBasePath = new URL(domEffect.getBaseURI()).getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");;

                                if(previousBasePath.equals(currentBasePath)){
                                    effect.add(domEffect);
                                }else{
                                    //Handle URL change in the middle of a series of DOM Effects
                                    ApplicationLocationChange applicationLocationChange = new ApplicationLocationChange();
                                    applicationLocationChange.setFrom(new URL(effect.getBaseURIs().iterator().next()));
                                    applicationLocationChange.setTo(new URL(domEffect.getBaseURI()));

                                    //Set the location change timestamp as the average between the current and last domEffect timestamps.
                                    long lastTimestamp = effect.get(effect.size()-1).getTimestamp().toInstant().toEpochMilli();
                                    long thisTimestamp = domEffect.getTimestamp().toInstant().toEpochMilli();

                                    long applicationLocationChangeTimestamp = (lastTimestamp + thisTimestamp)/2;
                                    applicationLocationChange.setTimestamp(ZonedDateTime.ofInstant(Instant.ofEpochMilli(applicationLocationChangeTimestamp), domEffect.getTimestamp().getZone()));

                                    line.add(applicationLocationChange);

                                    //Create the Effect following the ApplicationLocationChange.

                                    Effect postEffect = new Effect();
                                    postEffect.add(domEffect);
                                    line.add(postEffect);
                                }



                            }

                            if(line.last() == null || !(line.last() instanceof Effect)){

                                String currentBasePath = getNormalizedPath(domEffect.getBaseURI());
                                String lastBasePath = null;
                                String lastURL = null;
                                //Get the timestamp of the last entity in the timeline if it exists otherwise just use the same timestamp as the current dom effect
                                long lastTimestamp = line.last() == null?domEffect.getTimestamp().toInstant().toEpochMilli():line.last().timestamp();

                                if(line.last() instanceof NetworkEvent){
                                    NetworkEvent lastEntity = (NetworkEvent) line.last();
                                    lastURL = lastEntity.getRequestHeader("Referer");
                                    lastBasePath = getNormalizedPath(lastURL);

                                }

                                if(line.last() instanceof ClickEvent){
                                    ClickEvent lastEntity = (ClickEvent) line.last();
                                    lastURL = lastEntity.getBaseURI();
                                    lastBasePath = getNormalizedPath(lastURL);
                                }

                                //I don't think this one should ever be possible...
                                if(line.last() instanceof DataEntry){
                                    DataEntry lastEntity = (DataEntry)line.last();
                                    lastURL = lastEntity.lastChange().getBaseURI();
                                    lastBasePath = getNormalizedPath(lastURL);
                                }

                                if(lastBasePath != null && !currentBasePath.equals(lastBasePath)){

                                    //Handle URL change preceding this DOM Effect
                                    ApplicationLocationChange applicationLocationChange = new ApplicationLocationChange();
                                    applicationLocationChange.setFrom(lastURL);
                                    applicationLocationChange.setTo(domEffect.getBaseURI());

                                    long thisTimestamp = domEffect.getTimestamp().toInstant().toEpochMilli();
                                    long applicationLocationChangeTimestamp = (lastTimestamp + thisTimestamp)/2;
                                    applicationLocationChange.setTimestamp(ZonedDateTime.ofInstant(Instant.ofEpochMilli(applicationLocationChangeTimestamp), domEffect.getTimestamp().getZone()));

                                    line.add(applicationLocationChange);
                                }


                                Effect effect = new Effect();
                                effect.add(domEffect);
                                line.add(effect);
                            }

                            log.info("handled DOM_EFFECT");
                        }catch (Exception e){
                            log.error(e.getMessage(), e);
                        }

                    }
                    case NETWORK_EVENT -> {

                            NetworkEvent networkEvent = networkEventMapper.map(event);
                            networkEvent.setTimestamp(ZonedDateTime.parse(event.getString(TIMESTAMP_FIELD), timeFormatter));

                            if(artifactConsumer != null){ //If we have an artifact consumer set
                                artifactConsumer.accept(networkEvent); //Invoke them with the newly processed artifact.
                            }

                            log.info("{} - {}", networkEvent.getMethod(), networkEvent.getUrl());

                            //Use a network event filter if one is provided.
                            if(networkEventFilter != null && networkEventFilter.test(networkEvent)){
                                line.add(networkEvent);

                                //TODO - Temporarily ignore all GET requests. See 'Integrating Network Events # Network Event Summarization Options' in obsidian for details
                            }else if(!networkEvent.getMethod().toLowerCase().equals("get")) {
                                //line.add(networkEvent);

                                if(hasTriggeringClick(line.listIterator(line.size()))){
                                    line.add(networkEvent);
                                }




                            }

                            log.info("Handled NETWORK_EVENT");
                    }
                }


        }
    }

    /**
     * Searches backwards using the given iterator for a ClickEvent. ClickEvent must appear before the start of the timeline or before the last ApplicationLocationChange in order
     * for this method to return true.
     *
     * @param iterator
     * @return
     */
    private boolean hasTriggeringClick(ListIterator<TimelineEntity> iterator){


        while (iterator.hasPrevious()){
            TimelineEntity entity = iterator.previous();

            if(entity instanceof ClickEvent){
                return true;
            }

            if(entity instanceof ApplicationLocationChange){
                return false;
            }
        }

        return false;
    }


}
