package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.LogUIClickEventMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.LogUIDomEffectMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.LogUIInputChangeMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.LogUINetworkEventMapper;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static ca.ualberta.odobot.semanticflow.Utils.getNormalizedPath;

/**
 * An on-line version of {@link ca.ualberta.odobot.semanticflow.SemanticSequencer}.
 *
 *
 */
public class OnlineEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(OnlineEventProcessor.class);
    public static DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    private Map<Consumer<TimelineEntity>, Predicate<TimelineEntity>> listeners = new HashMap<>();

    private JsonMapper<ClickEvent> clickEventMapper = new LogUIClickEventMapper();
    private JsonMapper<DomEffect> domEffectMapper = new LogUIDomEffectMapper();
    private JsonMapper<NetworkEvent> networkEventMapper = new LogUINetworkEventMapper();
    private JsonMapper<InputChange> inputChangeMapper = new LogUIInputChangeMapper();
    private OnlineTimeline line = new OnlineTimeline();

    public OnlineEventProcessor(){
        line.addListener(this::notify);
    }

    /**
     * Set a method to call when a timeline entity is processed.
     * @param consumer
     */
    public void setOnEntity(Consumer<TimelineEntity> consumer){
        listeners.put(consumer, (entity -> true));
    }

    /**
     * Set a method to call when a timeline entity is processed. Method is only called if the processed entity satisfies the given predicate.
     * @param consumer
     * @param predicate
     */
    public void setOnEntity(Consumer<TimelineEntity> consumer, Predicate<TimelineEntity> predicate){
        listeners.put(consumer, predicate);
    }

    public void process(JsonArray events){
        events.stream()
                .map(o->(JsonObject)o)
                .forEach(this::process);
    }

    public void process(List<JsonObject> events){
        events.forEach(this::process);
    }

    public void process(JsonObject event){
        try{

//            int _preprocessLineSize = line.size();

            JsonObject eventDetails = event.getJsonObject("eventDetails");

            String eventType = event.getString("eventType");
            String eventName = eventDetails.getString("name");

//            log.info("event type: {}", eventType);
//            log.info("event name: {}", eventName);

            switch (eventType){
                case "interactionEvent":
                    switch (InteractionType.getType(eventName)){
                        case CLICK ->processClickEvent(event);
                        case INPUT ->processInputChange(event);
                    }
                    break;
                case "customEvent":
                    switch (InteractionType.getType(eventName)){
                        case DOM_EFFECT -> processDomEffect(event);
                        case NETWORK_EVENT -> processNetworkEvent(event);
                    }
                    break;
            }

//            if(_preprocessLineSize != line.size()){ //Only notify if a new entity has been added to the line. IE: if the line size has changed. For example, a GET network request is ignored, thus no change in line size.
//                if(line.size() > 0){
//                    log.info("Notifying of {}", line.last().symbol());
//                    notify(line.last());
//                }
//
//                if(line.size() > 2){
//                    line.remove(0);
//                }
//            }

        }catch (Exception e){
            log.error(e.getMessage(), e);
        }

    }

    private ZonedDateTime parseTimestamp(JsonObject event){
        String timestampString = event.getJsonObject("timestamps").getString("eventTimestamp");
        return ZonedDateTime.parse(timestampString, timeFormatter);
    }

    private void processNetworkEvent(JsonObject event){
        NetworkEvent networkEvent = networkEventMapper.map(event);
        networkEvent.setTimestamp(parseTimestamp(event));

        log.info("{} - {}", networkEvent.getMethod(), networkEvent.getUrl());

        if(!networkEvent.getMethod().toLowerCase().equals("get")){
            line.add(networkEvent);
        }

    }

    private void processDomEffect(JsonObject event){
        try{
            DomEffect domEffect = domEffectMapper.map(event);

            if(domEffect == null) {return;} //TODO - I wonder why this was necessary
            domEffect.setTimestamp(parseTimestamp(event));


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
                    effect.getBaseURIs().forEach(uri->log.error("{}", uri));
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


        }catch (Exception e){
            log.error(e.getMessage(), e);
        }
    }

    private void processInputChange(JsonObject event){
        InputChange inputChange = inputChangeMapper.map(event);
        inputChange.setTimestamp(parseTimestamp(event));

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
                return;
            }else{
                return;
            }
        }

        if(line.last() == null || !(line.last() instanceof DataEntry)){
            DataEntry dataEntry = new DataEntry();
            dataEntry.add(inputChange);
            line.add(dataEntry);
            return;
        }

        //Should never get here.
        //throw new RuntimeException("Unexpected error while processing input change!");
    }

    private void processClickEvent(JsonObject event){
        ClickEvent clickEvent = clickEventMapper.map(event);
        clickEvent.setTimestamp(parseTimestamp(event));

        /**
         * Special Case:
         * If the last element in the timeline is also a click event, only add this click event if the xpaths differ.
         * This arises from the fact that Odo-Sight can report the same click multiple times.
         * Since we listen for click on <a> and <li> tags, if we have an element <li><a/></li> our listener will
         * report the click twice, once for the <a> tag, and once for the <li> tag.
         */
        if(line.last() != null && line.last() instanceof ClickEvent && ((ClickEvent)line.last()).getXpath().equals(clickEvent.getXpath())){
            return;
        }

        line.add(clickEvent);

    }

    private void notify(TimelineEntity entity){

        if(listeners.size() > 0){ //If we have listeners registered for timeline entity notifications...
            listeners.forEach((listener,predicate)->{
                //Go through each of them, and if the timeline entity matches the listener's associated predicate, notify them.
                if(predicate.test(entity)){
                    listener.accept(entity);
                }
            });

        }
    }



}
