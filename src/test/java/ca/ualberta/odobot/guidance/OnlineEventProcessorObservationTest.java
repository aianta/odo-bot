package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Observations of uncharted steps only go to the uncharted timeline, so they never split an Effect or a DataEntry.
 */
class OnlineEventProcessorObservationTest {

    private OnlineEventProcessor processor;
    private final List<TimelineEntity> timeline = new ArrayList<>();
    private final List<Observation> unchartedTimeline = new ArrayList<>();

    @BeforeEach
    void setUp() {
        processor = new OnlineEventProcessor();
        processor.setOnEntity(timeline::add);
        processor.setOnUnchartedObservation(unchartedTimeline::add);
        processor.setUnchartedTimelineEnabled(true);
    }

    @Test
    void theUnchartedTimelineIsOnlyBuiltInUnchartedMode() {
        processor.setUnchartedTimelineEnabled(false);

        processor.process(observation("START_TRANSMISSION"));
        processor.addUnchartedObservation(new Observation(Observation.Trigger.UNCHARTED_ACTION, new JsonObject(), null, null, 0));

        assertEquals(1, timeline.size(), "the main timeline is built in every mode");
        assertTrue(unchartedTimeline.isEmpty());
    }

    @Test
    void theStartTransmissionObservationGoesToBothTimelines() {
        processor.process(observation("START_TRANSMISSION"));

        assertEquals(1, timeline.size());
        assertEquals(1, unchartedTimeline.size());
        assertSame(timeline.get(0), unchartedTimeline.get(0));
        assertEquals("https://canvas.example.com/courses/1", unchartedTimeline.get(0).getUserLocation());
    }

    @Test
    void anObservationWithoutATriggerIsAStartTransmissionOne() {
        JsonObject event = observation("START_TRANSMISSION");
        event.getJsonObject("eventDetails").remove("trigger");

        processor.process(event);

        assertEquals(Observation.Trigger.START_TRANSMISSION, unchartedTimeline.get(0).getTrigger());
        assertEquals(1, timeline.size());
    }

    @Test
    void observationsOfUnchartedActionsOnlyGoToTheUnchartedTimeline() {
        JsonObject event = observation("UNCHARTED_ACTION");
        event.getJsonObject("eventDetails").put("triggerDetails", new JsonObject().put("action", "scroll").put("amount", -5));
        processor.process(event);

        Observation harnessObservation = new Observation(Observation.Trigger.UNCHARTED_ACTION, new JsonObject().put("action", "wait"),
                null, null, 0);
        processor.addUnchartedObservation(harnessObservation);

        assertTrue(timeline.isEmpty());
        assertEquals(2, unchartedTimeline.size());
        assertEquals("scroll", unchartedTimeline.get(0).getTriggerDetails().getString("action"));
        assertSame(harnessObservation, unchartedTimeline.get(1));
    }

    private static JsonObject observation(String trigger) {
        return new JsonObject()
                .put("eventType", "customEvent")
                .put("eventDetails", new JsonObject()
                        .put("name", "OBSERVATION")
                        .put("trigger", trigger)
                        .put("userLocation", "https://canvas.example.com/courses/1"));
    }
}
