package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineDiagnosticsTest {

    private long now = 10_000;
    private final OnlineEventProcessor processor = new OnlineEventProcessor();
    private final PipelineDiagnostics diagnostics = new PipelineDiagnostics(processor, () -> now);

    private static JsonObject pageReady(String pageId, String url) {
        return new JsonObject().put("type", "PAGE_READY").put("pageId", pageId).put("url", url);
    }

    @Test
    void aPageThatSendsOnlyNetworkEventsAfterLoadingIsDeafAndOneThatSendsInteractionsIsNot() {
        diagnostics.onPage(pageReady("p1", "http://shop/admin/dashboard/"));
        processor.process(networkEvent(1, "POST", "http://shop/admin/mui/index/render/"));
        now += 6_000;
        diagnostics.tick();

        JsonObject report = diagnostics.report();
        JsonObject dashboard = report.getJsonObject("currentPage");
        assertEquals("http://shop/admin/dashboard/", dashboard.getString("url"));
        assertEquals(1, dashboard.getInteger("events"));
        assertEquals(0, dashboard.getInteger("pageSideEvents"));
        assertTrue(dashboard.getBoolean("deaf"), "loaded 6 s ago, and only the background reported anything");

        diagnostics.onPage(pageReady("p2", "http://shop/admin/catalog/product/"));
        processor.process(click(2, "page", null));
        processor.process(domEffect(3, "add", "/html/body/div", "Saved", "x"));
        diagnostics.tick(); //The session ticks every 500 ms; events are timed when a tick takes them in.
        now += 6_000;
        report = diagnostics.report();

        JsonObject products = report.getJsonObject("currentPage");
        assertEquals(2, products.getInteger("pageSideEvents"));
        assertFalse(products.getBoolean("deaf"));
        assertEquals(2, report.getJsonArray("pages").size());
        assertNotNull(report.getJsonArray("pages").getJsonObject(0).getLong("goneAt"), "the dashboard is gone once the next page is ready");
        assertEquals(1, report.getJsonObject("eventsByName").getInteger("NETWORK_EVENT"));
        assertEquals(now - 6_000, report.getLong("lastPageSideAt"));
        assertEquals(2, report.getInteger("lastEventSeq"));
    }

    @Test
    void aPageJustLoadedIsNotDeafYet() {
        diagnostics.onPage(pageReady("p1", "http://shop/admin/dashboard/"));
        now += 1_000;
        assertFalse(diagnostics.report().getJsonObject("currentPage").getBoolean("deaf"));
    }
}
