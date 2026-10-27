package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * What OdoBot sees of a recording session's event pipeline: the events that arrived, by name, and when; the gaps OdoX
 * reported; and, per page OdoX reported ready, how many <em>page-side</em> events (interactions and DOM effects, which
 * come from the scripts OdoX injects into the page, unlike network events, which its background records) arrived since
 * the page loaded. A page that loaded but never sent a page-side event is "deaf": its injected scripts are not
 * reporting, as on the Magento admin pages of the 'apple' run.
 *
 * <p>Fed by {@link #tick()} (new events) and {@link #onPage} (PAGE_READY / PAGE_GONE). Used on the session's context.</p>
 */
public final class PipelineDiagnostics {

    /**
     * How long after a page loaded with no page-side event it counts as deaf.
     */
    static final long DEAF_AFTER_MS = 5_000;

    private static final int MAX_PAGES = 50;

    private final OnlineEventProcessor processor;
    private final LongSupplier clock;

    private int lastSeen = -1;
    private Long lastEventAt;
    private Long lastPageSideAt;
    private final Map<String, Integer> counts = new TreeMap<>();
    private final List<Page> pages = new ArrayList<>();

    private static final class Page {
        final String pageId;
        final String url;
        final long readyAt;
        Long goneAt;
        int pageSideEvents;
        int events;
        Long lastPageSideAt;

        Page(String pageId, String url, long readyAt) {
            this.pageId = pageId;
            this.url = url;
            this.readyAt = readyAt;
        }

        JsonObject toJson(long now) {
            long alive = (goneAt == null ? now : goneAt) - readyAt;
            return new JsonObject()
                    .put("pageId", pageId)
                    .put("url", url)
                    .put("readyAt", readyAt)
                    .put("goneAt", goneAt)
                    .put("events", events)
                    .put("pageSideEvents", pageSideEvents)
                    .put("lastPageSideAt", lastPageSideAt)
                    .put("deaf", pageSideEvents == 0 && alive >= DEAF_AFTER_MS);
        }
    }

    public PipelineDiagnostics(OnlineEventProcessor processor) {
        this(processor, System::currentTimeMillis);
    }

    PipelineDiagnostics(OnlineEventProcessor processor, LongSupplier clock) {
        this.processor = processor;
        this.clock = clock;
    }

    /**
     * Take in the events that arrived since the last tick.
     */
    public void tick() {
        int last = processor.lastEventIndex();
        if (last <= lastSeen) {
            return;
        }
        long now = clock.getAsLong();
        lastEventAt = now;
        Page page = currentPage();
        for (OnlineEventProcessor.EventSummary event : processor.eventsIn(lastSeen, last)) {
            String name = event.name() == null ? event.eventType() : event.name();
            counts.merge(name, 1, Integer::sum);
            if (page != null) {
                page.events++;
            }
            if (isPageSide(event)) {
                lastPageSideAt = now;
                if (page != null) {
                    page.pageSideEvents++;
                    page.lastPageSideAt = now;
                }
            }
        }
        lastSeen = last;
    }

    /**
     * @return whether the event came from the scripts OdoX injects into the page: LogUI's interaction events and the
     * DOM effects and input changes OdoX sends as custom events.
     */
    static boolean isPageSide(OnlineEventProcessor.EventSummary event) {
        return "interactionEvent".equals(event.eventType()) || "DOM_EFFECT".equals(event.name()) || "INPUT_CHANGE".equals(event.name());
    }

    /**
     * A PAGE_READY or PAGE_GONE message from OdoX.
     */
    public void onPage(JsonObject message) {
        tick();
        String type = message.getString("type");
        String pageId = message.getString("pageId");
        if ("PAGE_READY".equals(type)) {
            Page current = currentPage();
            if (current != null && current.goneAt == null) {
                current.goneAt = clock.getAsLong();
            }
            pages.add(new Page(pageId, message.getString("url"), clock.getAsLong()));
            if (pages.size() > MAX_PAGES) {
                pages.remove(0);
            }
        } else if ("PAGE_GONE".equals(type)) {
            for (Page page : pages) {
                if (page.goneAt == null && (pageId == null || pageId.equals(page.pageId))) {
                    page.goneAt = clock.getAsLong();
                }
            }
        }
    }

    private Page currentPage() {
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }

    /**
     * @return OdoBot's side of the diagnostics: {@code {now, lastEventSeq, lastEventAt, lastPageSideAt, eventsByName,
     * gaps, currentPage, pages}}, newest page last.
     */
    public JsonObject report() {
        tick();
        long now = clock.getAsLong();
        JsonObject eventsByName = new JsonObject();
        counts.forEach(eventsByName::put);
        Page current = currentPage();
        return new JsonObject()
                .put("now", now)
                .put("lastEventSeq", lastSeen)
                .put("lastEventAt", lastEventAt)
                .put("lastPageSideAt", lastPageSideAt)
                .put("eventsByName", eventsByName)
                .put("gaps", counts.getOrDefault("EVENT_GAP", 0))
                .put("currentPage", current == null ? null : current.toJson(now))
                .put("pages", new JsonArray(pages.stream().map(p -> p.toJson(now)).toList()));
    }
}
