package ca.ualberta.odobot.debugger;

import io.vertx.core.json.JsonObject;

/**
 * The live recording session, if one runs. Only one runs at a time, since its events go to the Trajectory Debugger's
 * active timeline.
 *
 * <p>Event bus addresses: a session publishes what happens to it on {@value #EVENTS}, and answers requests on
 * {@link #address(String)}: {@code {action: row | transmit | diagnostics | end}}.</p>
 */
public final class RecordingSessions {

    /**
     * Published by a session: {@code {type: session-started | transmitting | entity | frozen | session-ended, sessionId, ...}}.
     */
    public static final String EVENTS = "debugger.live";

    private static JsonObject current;

    private RecordingSessions() {
    }

    public static String address(String sessionId) {
        return "debugger.session." + sessionId;
    }

    /**
     * Claim the single session slot.
     *
     * @return true if it was free.
     */
    public static synchronized boolean claim(JsonObject session) {
        if (current != null) {
            return false;
        }
        current = session.copy();
        return true;
    }

    /**
     * Update what is known of the current session, e.g. once it is recording.
     */
    public static synchronized void update(String sessionId, JsonObject fields) {
        if (current != null && sessionId.equals(current.getString("sessionId"))) {
            current.mergeIn(fields);
        }
    }

    public static synchronized void release(String sessionId) {
        if (current != null && sessionId.equals(current.getString("sessionId"))) {
            current = null;
        }
    }

    /**
     * @return the current session ({@code sessionId, state, viewerUrl, debuggerUrl, startUrl, startedAt, transmitting}), or null.
     */
    public static synchronized JsonObject current() {
        return current == null ? null : current.copy();
    }
}
