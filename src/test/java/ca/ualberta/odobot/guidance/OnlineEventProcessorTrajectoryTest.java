package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each top-level timeline entity remembers where in the task's raw events it came from, which is what links an
 * uncharted step to the charted entities it caused.
 */
class OnlineEventProcessorTrajectoryTest {

    private OnlineEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OnlineEventProcessor();
    }

    @Test
    void anEffectRecordsEveryDomEffectItWasBuiltFrom() {
        processor.process(click(2_000, "before", null));                                   //0
        processor.process(domEffect(2_100, "add", "/html/body/div[2]", "Saved", "a"));     //1
        processor.process(domEffect(2_200, "show", "/html/body/div[3]", "Toast", "b"));    //2
        processor.process(domEffect(2_300, "remove", "/html/body/div[4]", "Spinner", "c"));//3
        processor.process(click(2_400, "after", null));                                    //4

        List<TimelineEntity> timeline = processor.timeline();
        assertEquals(List.of("CE", "E", "CE"), timeline.stream().map(TimelineEntity::symbol).toList());
        assertEquals(List.of(0), processor.sourceEvents(timeline.get(0)));
        assertEquals(List.of(1, 2, 3), processor.sourceEvents(timeline.get(1)));
        assertEquals(List.of(4), processor.sourceEvents(timeline.get(2)));
        assertEquals(4, processor.lastEventIndex());
    }

    @Test
    void aDataEntryRecordsEveryInputChangeAndTheLatestFacts() {
        processor.process(input(3_000, "S", "one"));
        processor.process(input(3_100, "Sh", "two"));
        processor.process(input(3_200, "Shirt", "three"));

        TimelineEntity entry = processor.timeline().get(0);
        assertTrue(entry instanceof DataEntry);
        assertEquals(List.of(0, 1, 2), processor.sourceEvents(entry));
        assertEquals("INPUT_CHANGE", processor.eventFactsOf(entry).getString("name"));
        assertEquals(false, processor.eventFactsOf(entry).getBoolean("checked"));
        assertEquals("Shirt", ((DataEntry) entry).getEnteredData());
    }

    @Test
    void clicksKeepTheirLogUiNameWhichTheEntityDrops() {
        processor.process(click(2_000, "page", null));
        assertEquals("BTN_CLICK", processor.eventFactsOf(processor.timeline().get(0)).getString("name"));
    }

    @Test
    void pageSnapshotsAreOnlyKeptWithTrajectoryCapture() {
        processor.process(click(2_000, "without", null));
        assertNull(processor.domSnapshotOf(processor.timeline().get(0)));

        processor.setTrajectoryCapture(true);
        processor.process(domEffect(2_100, "add", "/html/body/div[2]", "x", "first"));
        processor.process(domEffect(2_200, "add", "/html/body/div[3]", "y", "latest"));

        TimelineEntity effect = processor.timeline().get(1);
        assertEquals(page("latest"), processor.domSnapshotOf(effect), "an Effect keeps the page of its latest DOM effect");
    }

    @Test
    void getRequestsAreFilteredOutButStillTakeAPositionInTheRawEvents() {
        processor.process(networkEvent(2_000, "GET", "http://shop.test/admin/catalog/product/grid"));
        processor.process(networkEvent(2_100, "POST", "http://shop.test/admin/catalog/product/save/"));

        List<TimelineEntity> timeline = processor.timeline();
        assertEquals(1, timeline.size());
        assertEquals(List.of(1), processor.sourceEvents(timeline.get(0)), "positions are positions in the saved event file");
    }

    @Test
    void taskAnswersArePositionedAndHarnessObservationsHaveNoSourceEvents() {
        processor.setUnchartedTimelineEnabled(true);
        processor.process(startObservation(null));
        processor.addUnchartedObservation(new Observation(Observation.Trigger.UNCHARTED_ACTION, null, null, null, 0, 1, "exec-1"));
        processor.injectTaskAnswer("42");

        List<TimelineEntity> uncharted = processor.unchartedTimeline();
        assertEquals(List.of(0), processor.sourceEvents(uncharted.get(0)));
        assertEquals(List.of(), processor.sourceEvents(uncharted.get(1)), "made from GET_SCREENSHOT, not a raw event");
        assertEquals(1, ((Observation) uncharted.get(1)).getStepNumber());

        TimelineEntity answer = processor.timeline().get(1);
        assertTrue(answer instanceof TaskAnswer);
        assertEquals(List.of(1), processor.sourceEvents(answer));
    }
}
