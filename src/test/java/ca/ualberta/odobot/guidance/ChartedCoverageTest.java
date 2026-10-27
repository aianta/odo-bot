package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.OnlineEventProcessor.EventSummary;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.trajectory.AgentTrace;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An uncharted step that is not followed by an interaction on the charted timeline is flagged, with a verdict that says
 * whether OdoX's logUI or OdoBot misses it.
 */
class ChartedCoverageTest {

    private static final JsonObject CLICK = new JsonObject().put("action", "left_click").put("x", 140).put("y", 65);
    private static final String OTHER_URL = "http://shop.test/admin/catalog/product/edit/1/";

    private OnlineEventProcessor processor;
    private StepLedger ledger;

    @BeforeEach
    void setUp() {
        processor = new OnlineEventProcessor();
        ledger = new StepLedger();
    }

    /**
     * Send step 1, let the events arrive, report it done and observe it at {@code urlAfter}.
     */
    private JsonObject step(JsonObject action, String status, String urlAfter, JsonObject target, JsonObject... events) {
        ledger.sent(1, "exec-1", 1_000, processor.lastEventIndex());
        for (JsonObject event : events) {
            processor.process(event);
        }
        ledger.done(1, 2_000, status, null, target);
        Observation result = new Observation(Observation.Trigger.UNCHARTED_ACTION, action, urlAfter, null, 3_000, 1, "exec-1");
        ledger.observed(1, result, 3_000, processor.lastEventIndex());
        return ledger.assessChartedCoverage(1, processor);
    }

    private static JsonObject unknownClick(long millis) {
        JsonObject click = click(millis, "clicked", null);
        click.getJsonObject("eventDetails").put("name", "DIV_CLICK");
        return click;
    }

    @Test
    void aClickOnTheChartedTimelineIsCharted() {
        JsonObject coverage = step(CLICK, "ok", BASE_URI, null, click(1_010, "clicked", null), domEffect(1_020, "add", "/html/body/div[2]", "Saved", "saved"));

        assertEquals("charted", coverage.getString("verdict"));
        assertEquals(1, coverage.getInteger("charted_interactions"));
        assertEquals(new JsonObject().put("BTN_CLICK", 1).put("DOM_EFFECT", 1), coverage.getJsonObject("events"));
        assertEquals(new JsonArray().add(-1).add(1), coverage.getJsonArray("window"));
        assertSame(coverage, ledger.entries().get(0).chartedCoverage, "the ledger keeps it for the trajectory");
    }

    @Test
    void typingThatChangesAnInputIsCharted() {
        JsonObject type = new JsonObject().put("action", "type").put("text", "Shirt");
        assertEquals("charted", step(type, "ok", BASE_URI, null, input(1_010, "Shirt", "typed")).getString("verdict"));
    }

    @Test
    void anInteractionOdoBotDoesNotMapIsUnmapped() {
        JsonObject coverage = step(CLICK, "ok", BASE_URI, null, unknownClick(1_010), domEffect(1_020, "add", "/html/body/div[2]", "Menu", "menu"));

        assertEquals("unmapped", coverage.getString("verdict"));
        assertEquals(new JsonArray().add("DIV_CLICK"), coverage.getJsonArray("unmapped_interactions"));
        assertEquals(0, coverage.getInteger("charted_interactions"));
    }

    @Test
    void effectsWithoutAnInteractionAreUninstrumented() {
        JsonObject target = new JsonObject().put("tagName", "div").put("className", "dropdown toggle").put("xpath", "/html/body/div[4]");
        JsonObject coverage = step(CLICK, "ok", BASE_URI, target,
                domEffect(1_020, "add", "/html/body/div[2]", "Menu", "menu"), networkEvent(1_030, "GET", "http://shop.test/api/menu"));

        assertEquals("uninstrumented", coverage.getString("verdict"));
        assertEquals(new JsonObject().put("DOM_EFFECT", 1).put("NETWORK_EVENT", 1), coverage.getJsonObject("events"));
        assertEquals(target, coverage.getJsonObject("target"));
        assertEquals(CLICK, coverage.getJsonObject("action"));
    }

    @Test
    void aUrlChangeWithoutAnyEventIsUninstrumented() {
        Observation input = new Observation(Observation.Trigger.UNCHARTED_ACTION, null, BASE_URI, null, 0, 0, "exec-0");
        ledger.emitted(1, "uncharted_step", new AgentTrace(1, input, "Action: click", null, "Action: click", "Click", CLICK,
                List.of(), "NONE", null, new JsonArray(), "gen-1", null, null, null, null, null, List.of(), null,
                100, 100, 100, 100, 0), null);
        JsonObject coverage = step(CLICK, "ok", OTHER_URL, null);

        assertEquals("uninstrumented", coverage.getString("verdict"));
        assertTrue(coverage.getBoolean("url_changed"));
    }

    @Test
    void lostEventsWithoutAnInteractionAreAnEventGap() {
        ledger.sent(1, "exec-1", 1_000, processor.lastEventIndex());
        processor.injectEventGap(3, 0, "sequence-gap", null);
        processor.process(domEffect(1_020, "add", "/html/body/div[2]", "Menu", "menu"));
        ledger.done(1, 2_000, "ok", null, null);
        ledger.observed(1, new Observation(Observation.Trigger.UNCHARTED_ACTION, CLICK, BASE_URI, null, 3_000, 1, "exec-1"),
                3_000, processor.lastEventIndex());

        assertEquals("event_gap", ledger.assessChartedCoverage(1, processor).getString("verdict"));
    }

    @Test
    void aStepWithoutAnyEventIsSilent() {
        JsonObject coverage = step(CLICK, "ok", BASE_URI, null);

        assertEquals("silent", coverage.getString("verdict"));
        assertEquals(new JsonObject(), coverage.getJsonObject("events"));
        assertFalse(coverage.getBoolean("url_changed"), "no URL before the step is known");
    }

    @Test
    void anInteractionBeforeTheStepWasSentDoesNotCount() {
        processor.process(click(500, "earlier", null));
        assertEquals("silent", step(CLICK, "ok", BASE_URI, null).getString("verdict"));
    }

    @Test
    void scrollsWaitsAndStepsThatDidNotRunAreNotChecked() {
        for (String action : List.of("scroll", "hscroll", "wait")) {
            JsonObject coverage = ChartedCoverage.assess(new JsonObject().put("action", action), "ok", false, new int[]{0, 1},
                    List.of(), null, null, null);
            assertEquals("not_checked", coverage.getString("verdict"), action);
            assertEquals("viewport action", coverage.getString("reason"));
        }
        assertEquals("done failed", ChartedCoverage.assess(CLICK, "failed", false, new int[]{0, 1}, List.of(), null, null, null).getString("reason"));
        assertEquals("done page-gone", ChartedCoverage.assess(CLICK, "page-gone", false, new int[]{0, 1}, List.of(), null, null, null).getString("reason"));
        assertEquals("lost", ChartedCoverage.assess(CLICK, null, true, new int[]{0, 1}, List.of(), null, null, null).getString("reason"));
        assertEquals("not observed", ChartedCoverage.assess(CLICK, "ok", false, null, List.of(), null, null, null).getString("reason"));
    }

    @Test
    void anInputChangeSentAsACustomEventIsAnInteraction() {
        JsonObject coverage = ChartedCoverage.assess(CLICK, "ok", false, new int[]{0, 1},
                List.of(new EventSummary(1, "customEvent", "INPUT_CHANGE", false)), null, null, null);
        assertEquals("unmapped", coverage.getString("verdict"));
    }

    @Test
    void onlyMissingInteractionsAreGaps() {
        assertFalse(ChartedCoverage.Verdict.CHARTED.isGap());
        assertFalse(ChartedCoverage.Verdict.NOT_CHECKED.isGap());
        assertTrue(ChartedCoverage.Verdict.UNINSTRUMENTED.isGap());
        assertTrue(ChartedCoverage.Verdict.SILENT.isGap());
        assertEquals(ChartedCoverage.Verdict.NOT_CHECKED, ChartedCoverage.verdictOf(null));
    }

    @Test
    void theTargetIsSummarizedOnOneLine() {
        JsonObject target = new JsonObject().put("tagName", "span").put("className", " label  big ").put("text", "My orders")
                .put("xpath", "/html/body/a/span")
                .put("focusTarget", new JsonObject().put("tagName", "a").put("id", "orders").put("xpath", "/html/body/a"));

        assertEquals("span.label.big \"My orders\" /html/body/a/span, focuses a#orders /html/body/a", ChartedCoverage.targetSummary(target));
        assertEquals("unknown", ChartedCoverage.targetSummary(null));
        assertEquals("unknown (boom)", ChartedCoverage.targetSummary(new JsonObject().put("error", "boom")));
    }
}
