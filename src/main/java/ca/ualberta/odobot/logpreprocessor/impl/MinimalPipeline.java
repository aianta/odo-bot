package ca.ualberta.odobot.logpreprocessor.impl;

import ca.ualberta.odobot.extractors.SemanticArtifactExtractor;
import ca.ualberta.odobot.semanticflow.SemanticSequencer;
import ca.ualberta.odobot.semanticflow.model.*;
import ca.ualberta.odobot.semanticflow.navmodel.nodes.*;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MinimalPipeline extends SimplePreprocessingPipeline{

    private static final Logger log = LoggerFactory.getLogger(MinimalPipeline.class);

    public MinimalPipeline(Vertx vertx, UUID id, String slug, String name) {
        super(vertx, id, slug, name);

        setId(id);
        setName(name);

        extractorMultimap.clear();

    }

    public Future<Timeline> makeTimeline(String flightName, List<JsonObject> events){
        log.info("{} events in trajectory {}", events.size(), flightName);

        SemanticSequencer sequencer = new SemanticSequencer(sqliteService);
//        sequencer.setNetworkEventFilter(networkEvent -> !networkEvent.getMethod().toLowerCase().equals("get") && //Exclude get requests
//                !networkEvent.getPath().equals("/api/v*/users/*/colors/course_*") //Exclude color API calls which are sometimes triggered automatically.
//        );


        //Timeline data structure construction
        Timeline timeline = sequencer.parse(events);

        //TODO: this will fail if we ever actually use flight names instead of IDs...
        UUID timelineId = UUID.fromString(flightName);
        timeline.setId(timelineId);

        timeline.getAnnotations().put("flight-name", flightName);

        int originalTimelineSize = timeline.size();


        /**
         * Semantic artifact extraction:
         *
         * Go through each timeline entity and get all matching extractors for that entity's class.
         * Then apply each extractor to the entity and add it's output to the entity's semantic artifacts.
         */
        ListIterator<TimelineEntity> it = timeline.listIterator();

        while (it.hasNext()){
            try{
                TimelineEntity entity = it.next();
                if(entity.symbol().equals("NET")){
                    continue;
                }

                Collection<SemanticArtifactExtractor> entityExtractors = extractorMultimap.get(entity.getClass());

                entityExtractors.forEach(extractor->
                        entity.getSemanticArtifacts().put(extractor.artifactName(), extractor.extract(entity,it.previousIndex(),timeline)));


            }catch (Exception e){
                log.error(e.getMessage(),e);
            }


        }


        return Future.succeededFuture(timeline);
    }

    public Future<Void> buildNavModel(Timeline timeline){

        neo4j.effectMap.clear();

        ListIterator<TimelineEntity> it = timeline.listIterator();
        int clickEventCount = 0;
        int dataEntryCount = 0;
        int networkEventCount = 0;
        int applicationLocationChangeCount = 0;
        int checkboxCount = 0;
        int radioButtonCount = 0;

        log.info("Building nav model for timeline: {} [{}]: {}", timeline.getId(), timeline.size(), timeline.toString());

        while (it.hasNext()){

            TimelineEntity entity = it.next();


            if(it.previousIndex() == 0){ //On the first entity of the timeline, get or create the location where the timeline starts.
                neo4j.getOrCreateLocation(TimelineEntity.getLocationPath(entity), timeline);
            }


            if(entity instanceof ClickEvent){
                ClickEvent clickEvent = (ClickEvent) entity;
                clickEventCount++;
                ClickNode clickNode = neo4j.processClickEvent(timeline, clickEvent);

                if(clickEvent.getResourceAnnotation() != null){
                    //If this click has a resource annotation, ensure an appropriate resource parameter node is created to represent it.
                    ResourceParameterNode resourceParameterNode = neo4j.processResourceParameter(clickEvent.getResourceAnnotation());
                    neo4j.connect(clickNode, resourceParameterNode, "PARAM");
                }

            }

            if(entity instanceof CheckboxEvent){
                checkboxCount++;
                neo4j.processCheckboxEvent(timeline, (CheckboxEvent) entity);
            }

            if(entity instanceof RadioButtonEvent){
                radioButtonCount++;
                neo4j.processRadioButtonEvent(timeline, (RadioButtonEvent) entity);
            }

            if(entity instanceof DataEntry){
                dataEntryCount++;
                neo4j.processDataEntry(timeline, (DataEntry) entity);
            }

            if(entity instanceof NetworkEvent){
                networkEventCount++;
                neo4j.processNetworkEvent(timeline, (NetworkEvent) entity);
            }

            if(entity instanceof ApplicationLocationChange){
                applicationLocationChangeCount++;
                neo4j.processApplicationLocationChange(timeline, (ApplicationLocationChange) entity);
            }

        }




        //Now do another pass to process effects.
        int effectCount = 0;
        it = timeline.listIterator();
        while (it.hasNext()){
            TimelineEntity entity = it.next();

            if(entity instanceof Effect && it.hasNext() && it.previousIndex() != 0){
                /**
                 * Do not model effects that happen at the end or beginning of a trace...
                 * Strange things can happen here, because an effect at the end of a trace can only be matched by its predecessor.
                 * If there have been other effects between that predecessor and some successor, wierd stuff happens.
                 */
                effectCount++;
                neo4j.processEffect(timeline, (Effect) entity);
            }


        }



        //Do a final pass connecting everything
        if(timeline.size()>2){
            it = timeline.listIterator();
            ListIterator<TimelineEntity> successorIt = timeline.listIterator();
            successorIt.next();

            /**
             * Need to define these out side the scope of the while loop so that the final event/node in a timeline/trajectory
             * can be saved in the event_node_index.
             */
            TimelineEntity curr = null;
            TimelineEntity next = null;
            NavNode a = null;
            NavNode b = null;

            while (it.hasNext() && successorIt.hasNext()){

                curr = it.next();
                next = successorIt.next();


                if((curr instanceof Effect && it.previousIndex() == 0) || (next instanceof Effect && !successorIt.hasNext())){
                    continue; //Ignore effects at the start or end of timelines
                }

                //Ignore double clicks
                if(curr instanceof ClickEvent && next instanceof ClickEvent && ((ClickEvent)curr).getXpath().equals(((ClickEvent) next).getXpath())){
                    continue;
                }

                //If this is the first element in the timeline
                if(it.previousIndex() == 0){
                    LocationNode startingLocation = neo4j.getLocationNode(TimelineEntity.getLocationPath(curr));
                    //Link the starting location to the first node.
                    neo4j.bind(startingLocation, neo4j.resolveNavNode(timeline, it.previousIndex()));
                }

                a = neo4j.resolveNavNode(timeline, it.previousIndex());
                b = neo4j.resolveNavNode(timeline, successorIt.previousIndex());

                log.info("a is {} at {}", curr.symbol(), it.previousIndex());
                log.info("b is {} at {}", next.symbol(), successorIt.previousIndex());

                neo4j.bind(a, b);

                int eventIndex = it.previousIndex();
                String eventId = timeline.getId().toString() + "#" + eventIndex;
                sqliteService.saveEventNodeMapping(eventId, a.getId().toString(), eventIndex, timeline.getId().toString());
            }

            //Save the mapping of the last event in the trajectory.
            int eventIndex = it.previousIndex();
            String eventId = timeline.getId().toString() + "#" + eventIndex;
            sqliteService.saveEventNodeMapping(eventId, a.getId().toString(), eventIndex, timeline.getId().toString());

            int lastEventIndex = successorIt.previousIndex();
            String lastEventId = timeline.getId().toString() + "#" + lastEventIndex;
            sqliteService.saveEventNodeMapping(lastEventId, b.getId().toString(), lastEventIndex, timeline.getId().toString());
        }else{
            throw new RuntimeException("Timeline size is too small! Timeline: %s".formatted(timeline.size()));
        }




        log.info("Processed {} clicks for nav model", clickEventCount);
        log.info("Processed {} data entries for nav model", dataEntryCount);
        log.info("Processed {} network events for nav model", networkEventCount);
        log.info("Processed {} effects for nav model", effectCount);
        log.info("Processed {} checkbox events for nav model", checkboxCount);
        log.info("Processed {} radio button events for nav model", radioButtonCount);
        log.info("Processed {} application location changes for nav model", applicationLocationChangeCount);


        return Future.succeededFuture();

    }
}
