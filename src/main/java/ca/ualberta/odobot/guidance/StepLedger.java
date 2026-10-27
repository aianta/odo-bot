package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.trajectory.AgentTrace;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The harness's record of a task's uncharted steps, from the agent's thoughts to the observation of the step's result,
 * saved with the task's trajectory.
 *
 * <p>Event positions are positions in the task's raw events (see {@link OnlineEventProcessor#lastEventIndex()}). A
 * step's window, {@code (eventAtSend, eventAtObservation]}, holds the events that arrived while it ran and its page
 * settled.</p>
 */
public class StepLedger {

    /**
     * One model step.
     */
    public static class Entry {

        public final int stepNumber;

        /**
         * What the agent thought, or null if it attached nothing.
         */
        public AgentTrace trace;

        /**
         * The instruction the step emitted first, e.g. "uncharted_step", "TaskComplete" or "GiveUp".
         */
        public String emitted;

        /**
         * Why the harness did not execute the step (e.g. the step limit), or null if it did or it executes nothing.
         */
        public String notExecuted;

        public String instructionId;
        public Long sentAt;
        public Integer eventAtSend;
        public Long doneAt;
        public String doneStatus;
        public String doneReason;

        /**
         * The element OdoX aimed the action at, as its EXECUTE_DONE described it, or null.
         */
        public JsonObject target;

        /**
         * True if OdoX never received the step, or never reported it done.
         */
        public boolean lost = false;

        /**
         * The observation of the step's result, or null.
         */
        public Observation resultObservation;
        public Long observedAt;
        public Integer eventAtObservation;

        /**
         * Whether the step shows up on the charted timeline, see {@link ChartedCoverage#assess}, or null until its result
         * is observed.
         */
        public JsonObject chartedCoverage;

        Entry(int stepNumber) {
            this.stepNumber = stepNumber;
        }
    }

    private final Map<Integer, Entry> entries = new TreeMap<>();

    private Entry entry(int stepNumber) {
        return entries.computeIfAbsent(stepNumber, Entry::new);
    }

    public synchronized void clear() {
        entries.clear();
    }

    /**
     * The agent emitted an instruction for a step. Only the first instruction of a step sets what it emitted.
     *
     * @param notExecuted why the harness won't execute it, or null.
     */
    public synchronized void emitted(int stepNumber, String instruction, AgentTrace trace, String notExecuted) {
        Entry entry = entry(stepNumber);
        if (entry.emitted == null) {
            entry.emitted = instruction;
        }
        if (trace != null && entry.trace == null) {
            entry.trace = trace;
        }
        if (notExecuted != null && entry.notExecuted == null) {
            entry.notExecuted = notExecuted;
        }
    }

    public synchronized void sent(int stepNumber, String instructionId, long sentAt, int eventAtSend) {
        Entry entry = entry(stepNumber);
        //A step observed again after it was lost is not sent again; the first send is the one that counts.
        if (entry.sentAt == null) {
            entry.instructionId = instructionId;
            entry.sentAt = sentAt;
            entry.eventAtSend = eventAtSend;
        }
    }

    /**
     * @param target the element OdoX aimed the action at, or null.
     */
    public synchronized void done(int stepNumber, long doneAt, String status, String reason, JsonObject target) {
        Entry entry = entry(stepNumber);
        entry.doneAt = doneAt;
        entry.doneStatus = status;
        entry.doneReason = reason;
        entry.target = target;
    }

    public synchronized void lost(int stepNumber) {
        entry(stepNumber).lost = true;
    }

    public synchronized void observed(int stepNumber, Observation observation, long observedAt, int eventAtObservation) {
        Entry entry = entry(stepNumber);
        entry.resultObservation = observation;
        entry.observedAt = observedAt;
        entry.eventAtObservation = eventAtObservation;
    }

    /**
     * Assess whether an observed step shows up on the charted timeline, see {@link ChartedCoverage}, and keep the
     * assessment with the step.
     *
     * @param processor the task's event processor, for the events of the step's window.
     * @return the assessment.
     */
    public synchronized JsonObject assessChartedCoverage(int stepNumber, OnlineEventProcessor processor) {
        Entry entry = entry(stepNumber);
        Observation result = entry.resultObservation;
        JsonObject action = result != null && result.getTriggerDetails() != null ? result.getTriggerDetails()
                : entry.trace != null ? entry.trace.action() : null;
        int[] window = entry.eventAtSend != null && entry.eventAtObservation != null
                ? new int[]{entry.eventAtSend, entry.eventAtObservation} : null;
        String urlBefore = entry.trace != null && entry.trace.inputObservation() instanceof Observation input
                ? input.getUserLocation() : null;
        String urlAfter = result != null ? result.getUserLocation() : null;
        entry.chartedCoverage = ChartedCoverage.assess(action, entry.doneStatus, entry.lost, window,
                window == null ? List.of() : processor.eventsIn(window[0], window[1]), urlBefore, urlAfter, entry.target);
        return entry.chartedCoverage;
    }

    /**
     * @return the steps, in step order.
     */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }
}
