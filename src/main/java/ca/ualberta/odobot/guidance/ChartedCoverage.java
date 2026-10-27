package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.guidance.OnlineEventProcessor.EventSummary;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether an uncharted step shows up on the charted timeline as an interaction.
 *
 * <p>The charted timeline of an uncharted run should hold every material action the agent took. Any action other than
 * one that only moves the viewport or waits ({@link #VIEWPORT_ACTIONS}) should therefore be followed, in the step's
 * window of raw events (see {@link StepLedger}), by an interaction event that made it onto the charted timeline: a
 * click, an input change or a select. A step where it isn't points at an interaction OdoX's logUI does not record, or
 * one OdoBot does not map, and its {@link Verdict} says which.</p>
 */
public final class ChartedCoverage {

    private ChartedCoverage() {
    }

    /**
     * Actions that only move the viewport or wait, which are not expected to produce an interaction event.
     */
    static final Set<String> VIEWPORT_ACTIONS = Set.of("scroll", "hscroll", "wait");

    public enum Verdict {
        /**
         * An interaction event of the step made it onto the charted timeline.
         */
        CHARTED,
        /**
         * Interaction events arrived, but none made it onto the charted timeline: OdoBot does not map them (e.g. a
         * click name {@link ca.ualberta.odobot.semanticflow.model.InteractionType} does not know) or drops them.
         */
        UNMAPPED,
        /**
         * No interaction event arrived, but events were lost on the way (EVENT_GAP), so it may have been among them.
         */
        EVENT_GAP,
        /**
         * No interaction event arrived, yet the step changed something: the page changed (DOM effects), the application
         * made requests, or the URL changed. logUI does not record the interaction.
         */
        UNINSTRUMENTED,
        /**
         * Nothing arrived and the URL did not change: the action most likely did nothing, e.g. a click on dead space.
         */
        SILENT,
        /**
         * The step isn't checked: it only moves the viewport or waits, it didn't run, or it was never observed.
         */
        NOT_CHECKED;

        public String label() {
            return name().toLowerCase();
        }

        /**
         * @return whether the verdict points at an interaction missing from the charted timeline.
         */
        public boolean isGap() {
            return this != CHARTED && this != NOT_CHECKED;
        }
    }

    /**
     * Assess a step once its result has been observed.
     *
     * @param action    the step's action as sent, e.g. {@code {"action": "left_click", "x": 10, "y": 20}}, or null.
     * @param status    the status OdoX reported the step done with, or null if it never did.
     * @param lost      whether OdoX never received the step, or never reported it done.
     * @param window    the step's window, {@code {fromExclusive, toInclusive}}, or null if it was never sent or observed.
     * @param events    the raw events of the window.
     * @param urlBefore the URL of the page the agent acted on, or null.
     * @param urlAfter  the URL of the observation of the step's result, or null.
     * @param target    the element OdoX aimed the action at, from its EXECUTE_DONE, or null.
     * @return the assessment: {@code verdict}, {@code reason} (for {@link Verdict#NOT_CHECKED}), {@code action} (as sent),
     * {@code window}, {@code events} (count by event name), {@code charted_interactions}, {@code unmapped_interactions}
     * (names of interaction events not on the charted timeline), {@code url_changed} and {@code target}.
     */
    public static JsonObject assess(JsonObject action, String status, boolean lost, int[] window, List<EventSummary> events,
                                    String urlBefore, String urlAfter, JsonObject target) {
        String actionName = action == null ? null : action.getString("action");
        JsonObject assessment = new JsonObject()
                .put("action", action)
                .put("target", target);
        if (window != null) {
            assessment.put("window", new JsonArray().add(window[0]).add(window[1]));
        }

        String notChecked = actionName == null ? "no action"
                : VIEWPORT_ACTIONS.contains(actionName) ? "viewport action"
                : lost ? "lost"
                : !"ok".equals(status) ? "done " + status
                : window == null ? "not observed"
                : null;
        if (notChecked != null) {
            return assessment.put("verdict", Verdict.NOT_CHECKED.label()).put("reason", notChecked);
        }

        TreeMap<String, Integer> counts = new TreeMap<>();
        JsonArray unmapped = new JsonArray();
        int charted = 0;
        boolean gap = false;
        boolean effects = false;
        for (EventSummary event : events) {
            String name = Objects.requireNonNullElse(event.name(), "?");
            counts.merge(name, 1, Integer::sum);
            if (isInteraction(event)) {
                if (event.onTimeline()) {
                    charted++;
                } else if (!unmapped.contains(name)) {
                    unmapped.add(name);
                }
            } else if ("EVENT_GAP".equals(name)) {
                gap = true;
            } else if ("DOM_EFFECT".equals(name) || "NETWORK_EVENT".equals(name)) {
                effects = true;
            }
        }
        boolean urlChanged = urlBefore != null && urlAfter != null && !urlBefore.equals(urlAfter);

        Verdict verdict = charted > 0 ? Verdict.CHARTED
                : !unmapped.isEmpty() ? Verdict.UNMAPPED
                : gap ? Verdict.EVENT_GAP
                : effects || urlChanged ? Verdict.UNINSTRUMENTED
                : Verdict.SILENT;

        return assessment.put("verdict", verdict.label())
                .put("events", new JsonObject(new TreeMap<String, Object>(counts)))
                .put("charted_interactions", charted)
                .put("unmapped_interactions", unmapped)
                .put("url_changed", urlChanged);
    }

    /**
     * Interaction events are logUI's interactionEvents, plus input changes, which OdoX also sends as custom events.
     */
    static boolean isInteraction(EventSummary event) {
        return "interactionEvent".equals(event.eventType()) || "INPUT_CHANGE".equals(event.name());
    }

    /**
     * @return a one-line description of the target OdoX reported, e.g. {@code span.label "My orders" /html/body/a/span,
     * focuses a /html/body/a}, or "unknown" if it reported none.
     */
    public static String targetSummary(JsonObject target) {
        if (target == null) {
            return "unknown";
        }
        if (target.getString("tagName") == null) {
            return "unknown (" + target.getString("error", "no tag") + ")";
        }
        StringBuilder summary = new StringBuilder(elementSummary(target));
        JsonObject focusTarget = target.getJsonObject("focusTarget");
        if (focusTarget != null) {
            summary.append(", focuses ").append(elementSummary(focusTarget));
        }
        return summary.toString();
    }

    private static String elementSummary(JsonObject element) {
        StringBuilder summary = new StringBuilder(element.getString("tagName"));
        if (element.getString("id") != null) {
            summary.append('#').append(element.getString("id"));
        }
        if (element.getString("className") != null && !element.getString("className").isBlank()) {
            summary.append('.').append(String.join(".", element.getString("className").trim().split("\\s+")));
        }
        if (element.getString("text") != null) {
            String text = element.getString("text");
            summary.append(" \"").append(text.length() > 40 ? text.substring(0, 40) + "…" : text).append('"');
        }
        if (element.getString("xpath") != null) {
            summary.append(' ').append(element.getString("xpath"));
        }
        return summary.toString();
    }

    /**
     * @return the assessment's verdict, or {@link Verdict#NOT_CHECKED} if it has none.
     */
    public static Verdict verdictOf(JsonObject assessment) {
        String verdict = assessment == null ? null : assessment.getString("verdict");
        return verdict == null ? Verdict.NOT_CHECKED : Verdict.valueOf(verdict.toUpperCase());
    }
}
