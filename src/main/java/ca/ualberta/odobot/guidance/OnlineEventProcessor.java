package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.Utils;
import ca.ualberta.odobot.semanticflow.mappers.JsonMapper;
import ca.ualberta.odobot.semanticflow.mappers.impl.*;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static ca.ualberta.odobot.common.Utils.getTerminalElementOfXpath;
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
    private JsonMapper<SelectEvent> selectEventJsonMapper = new LogUISelectEventMapper();
    //DOM effects can arrive many times a second, and nothing reads their parsed pages during a live run, so they are not parsed.
    private JsonMapper<DomEffect> domEffectMapper = new LogUIDomEffectMapper(false);
    private JsonMapper<NetworkEvent> networkEventMapper = new LogUINetworkEventMapper();
    private JsonMapper<InputChange> inputChangeMapper = new LogUIInputChangeMapper();
    private OnlineTimeline line = new OnlineTimeline();

    /**
     * The observations that feed uncharted agents: the one OdoX sends when transmission starts, which is also on {@link #line}, and one
     * per uncharted step, see {@link UnchartedStepObserver}.
     */
    private OnlineTimeline unchartedLine = new OnlineTimeline();

    /**
     * The uncharted timeline is only built in {@link ExecutionMode#UNCHARTED} mode.
     */
    private boolean unchartedTimelineEnabled = false;

    /**
     * Raw events received before {@link #streamRawEventsTo} opened the task's event file. Once it is open, raw events go
     * straight to the file, one JSON object per line, so a long task does not hold its whole event log (each DOM effect
     * carries a full page snapshot) in memory.
     */
    private final List<String> pendingRawEvents = new ArrayList<>();

    private Path rawEventsPartFile = null;

    private BufferedWriter rawEventsWriter = null;

    private int rawEventCount = 0;

    public void injectNoOp(){
        if(line != null){
            line.add(new NoOpEvent());
        }
    }

    /**
     * Records the answer an agent reported on completing its task: on the timeline, and as a TASK_ANSWER custom event in the
     * raw events, so that it is saved with them.
     */
    public void injectTaskAnswer(String answer){
        TaskAnswer taskAnswer = new TaskAnswer(answer);
        if(line != null){
            line.add(taskAnswer);
        }
        appendRawEvent(new JsonObject()
                .put("eventType", "customEvent")
                .put("eventDetails", new JsonObject()
                        .put("name", "TASK_ANSWER")
                        .put("answer", answer))
                .put("timestamps", new JsonObject()
                        .put("eventTimestamp", timeFormatter.format(Instant.ofEpochMilli(taskAnswer.timestamp()))))
                .encode());
    }

    /**
     * @param listener called with every observation added to the uncharted timeline.
     */
    public void setOnUnchartedObservation(Consumer<Observation> listener){
        unchartedLine.addListener(entity->listener.accept((Observation) entity));
    }

    /**
     * Adds an observation of an uncharted step to the uncharted timeline only.
     */
    public void addUnchartedObservation(Observation observation){
        if(!unchartedTimelineEnabled){
            log.warn("The uncharted timeline is disabled, dropping an observation of an uncharted step.");
            return;
        }
        unchartedLine.add(observation);
    }

    /**
     * @param enabled whether to build the uncharted timeline, which is only done in {@link ExecutionMode#UNCHARTED} mode.
     */
    public void setUnchartedTimelineEnabled(boolean enabled){
        this.unchartedTimelineEnabled = enabled;
    }

    /**
     * Drop the raw events recorded so far, and delete the task's partial event file if there is one.
     */
    public synchronized void clearRawEvents(){
        pendingRawEvents.clear();
        rawEventCount = 0;
        closeRawEventsWriter();
        if(rawEventsPartFile != null){
            try{
                Files.deleteIfExists(rawEventsPartFile);
            }catch (IOException e){
                log.warn("Could not delete the partial event file {}: {}", rawEventsPartFile, e.getMessage());
            }
            rawEventsPartFile = null;
        }
    }

    /**
     * Write the raw events of the task to the given partial file from now on, one JSON object per line, starting with
     * those received so far. {@link #saveRawEvents} turns it into the task's event file. If the file cannot be opened,
     * raw events stay in memory.
     *
     * @param partFile a file name that the experiment's skip check and the evaluation script do not mistake for a
     *                 task's event file, i.e. one not ending in {@code .json}.
     */
    public synchronized void streamRawEventsTo(Path partFile){
        closeRawEventsWriter();
        try{
            BufferedWriter writer = Files.newBufferedWriter(partFile, StandardCharsets.UTF_8);
            for(String event: pendingRawEvents){
                writer.write(event);
                writer.newLine();
            }
            pendingRawEvents.clear();
            rawEventsWriter = writer;
            rawEventsPartFile = partFile;
        }catch (IOException e){
            log.error("Could not open {} for raw events, keeping them in memory: {}", partFile, e.getMessage());
        }
    }

    private synchronized void appendRawEvent(String event){
        rawEventCount++;
        if(rawEventsWriter != null){
            try{
                rawEventsWriter.write(event);
                rawEventsWriter.newLine();
                return;
            }catch (IOException e){
                log.error("Could not write a raw event to {}, keeping the rest in memory: {}", rawEventsPartFile, e.getMessage());
                closeRawEventsWriter();
            }
        }
        pendingRawEvents.add(event);
    }

    private void closeRawEventsWriter(){
        if(rawEventsWriter != null){
            try{
                rawEventsWriter.close();
            }catch (IOException e){
                log.warn("Could not close the raw event file {}: {}", rawEventsPartFile, e.getMessage());
            }
            rawEventsWriter = null;
        }
    }

    public int countNetworkEvents(){
        int count = 0;
        Iterator<TimelineEntity> it = line.iterator();
        while (it.hasNext()){
            TimelineEntity t = it.next();
            if(t instanceof NetworkEvent){
                count++;
            }
        }
        return count;
    }

    /**
     * Save the raw events of the task as a JSON array in the given file. They are copied one at a time from the partial
     * file (then any held in memory), so the log is never held in memory as a whole. The array is written to a temporary
     * file and moved into place, so a failed save never leaves a partial event file that would count as a finished task.
     */
    public synchronized void saveRawEvents(String filename){
        closeRawEventsWriter();
        Path target = Path.of(filename);
        //Not ending in, or containing, "<id>.json", so that a leftover is never taken for a finished task.
        Path temp = Path.of(filename.replaceFirst("\\.json$", "") + ".events-saving");
        try{
            try(BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)){
                out.write("[");
                boolean first = true;
                if(rawEventsPartFile != null && Files.exists(rawEventsPartFile)){
                    try(BufferedReader in = Files.newBufferedReader(rawEventsPartFile, StandardCharsets.UTF_8)){
                        String line;
                        while((line = in.readLine()) != null){
                            if(line.isBlank()) continue;
                            first = writeArrayElement(out, line, first);
                        }
                    }
                }
                for(String event: pendingRawEvents){
                    first = writeArrayElement(out, event, first);
                }
                out.write("\n]");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved {} raw events to {}", rawEventCount, filename);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            try{
                Files.deleteIfExists(temp);
            }catch (IOException ignored){}
            throw new RuntimeException(e);
        }
    }

    private static boolean writeArrayElement(BufferedWriter out, String event, boolean first) throws IOException{
        out.write(first? "\n" : ",\n");
        out.write(event);
        return false;
    }

    public OnlineEventProcessor(){
        clearRawEvents();
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
        //Recorded before processing, which may change the event.
        appendRawEvent(event.encode());
        try{

//            int _preprocessLineSize = line.size();

            JsonObject eventDetails = event.getJsonObject("eventDetails");

            String eventType = event.getString("eventType");
            String eventName = eventDetails.getString("name");

            log.info("event type: {} - event name: {} - raw events size: {}", eventType, eventName, rawEventCount);

            switch (eventType){
                case "interactionEvent":
                    switch (InteractionType.getType(eventName)){
                        case CLICK ->processClickEvent(event);
                        case INPUT ->processInputChange(event);
                        case SELECT -> processSelectEvent(event);
                    }
                    break;
                case "customEvent":
                    switch (InteractionType.getType(eventName)){
                        case DOM_EFFECT -> processDomEffect(event);
                        case NETWORK_EVENT -> processNetworkEvent(event);
                        case INPUT -> processInputChange(event);
                        case OBSERVATION -> processObservation(event);
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

    private void processObservation(JsonObject event){
        JsonObject eventDetails = event.getJsonObject("eventDetails");

        //OdoX versions that predate triggers only send observations on START_TRANSMISSION.
        Observation.Trigger trigger = Observation.Trigger.valueOf(eventDetails.getString("trigger", Observation.Trigger.START_TRANSMISSION.name()));
        JsonObject triggerDetails = eventDetails.getJsonObject("triggerDetails");
        if(trigger == Observation.Trigger.UNCHARTED_ACTION && triggerDetails == null){
            log.warn("Observation triggered by an uncharted action carries no triggerDetails.");
        }

        String userLocation = eventDetails.getString("userLocation");
        Screenshot screenshot = Screenshot.fromBase64(eventDetails.getString("screenshot"));

        long timestamp = event.containsKey("timestamps")? parseTimestamp(event).toInstant().toEpochMilli() : Instant.now().toEpochMilli();
        Observation observation = new Observation(trigger, triggerDetails, userLocation, screenshot, timestamp);

        log.info("Observation triggered by {} at user location {}, with{} screenshot", trigger,
                userLocation != null? userLocation: "N/A", screenshot != null? "": "out");

        //Observations of uncharted actions only feed the uncharted agent, so they never split an Effect or a DataEntry.
        if(trigger == Observation.Trigger.START_TRANSMISSION){
            line.add(observation);
        }
        if(unchartedTimelineEnabled){
            unchartedLine.add(observation);
        }
    }

    private void processNetworkEvent(JsonObject event){
        NetworkEvent networkEvent = networkEventMapper.map(event);
        networkEvent.setTimestamp(parseTimestamp(event));

        log.info("{} - {}", networkEvent.getMethod(), networkEvent.getUrl());

        if(Utils.networkEventPredicate.test(networkEvent)){
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
                    //throw new RuntimeException("Invalid Effect BaseURI set size!");
                }

                String previousBasePath =new URL(effect.getBaseURIs().iterator().next()).getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");;
                String currentBasePath = domEffect.getBaseURI().getPath().replaceAll("[0-9]+", "*").replaceAll("(?<=pages\\/)[\\s\\S]+", "*");;

                if(previousBasePath.equals(currentBasePath)){
                    effect.add(domEffect);
                }else{
                    //Handle URL change in the middle of a series of DOM Effects
                    ApplicationLocationChange applicationLocationChange = new ApplicationLocationChange();
                    applicationLocationChange.setFrom(new URL(effect.getBaseURIs().iterator().next()));
                    applicationLocationChange.setTo(domEffect.getBaseURI());

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
                    lastURL = lastEntity.getBaseURI().toString();
                    lastBasePath = getNormalizedPath(lastURL);
                }

                //I don't think this one should ever be possible...
                if(line.last() instanceof DataEntry){
                    DataEntry lastEntity = (DataEntry)line.last();
                    lastURL = lastEntity.lastChange().getBaseURI().toString();
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
        log.info("Input change event:\n{}\n", event.encodePrettily());

        InputChange inputChange = inputChangeMapper.map(event);
        inputChange.setTimestamp(parseTimestamp(event));

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

    private void processSelectEvent(JsonObject event){
        SelectEvent selectEvent = selectEventJsonMapper.map(event);
        selectEvent.setTimestamp(parseTimestamp(event));

        line.add(selectEvent);
    }

    private void processClickEvent(JsonObject event){
        ClickEvent clickEvent = clickEventMapper.map(event);
        clickEvent.setTimestamp(parseTimestamp(event));

        /*
         * If the click event is a SPAN click, the terminal element of the click xpath should be a span.
         * Do this check after creating the click event so that xpath truncation can take place first.
         */
        var eventName = event.getJsonObject("eventDetails").getString("name");
        if(eventName.equals("SPAN_CLICK") && (!getTerminalElementOfXpath(clickEvent.getXpath()).contains("span") ||
                clickEvent.getTriggerElement().text().isEmpty())){
            return;
        }

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
