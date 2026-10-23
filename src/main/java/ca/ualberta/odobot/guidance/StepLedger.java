package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.trajectory.AgentTrace;

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
         * True if OdoX never received the step, or never reported it done.
         */
        public boolean lost = false;

        /**
         * The observation of the step's result, or null.
         */
        public Observation resultObservation;
        public Long observedAt;
        public Integer eventAtObservation;

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

    public synchronized void done(int stepNumber, long doneAt, String status, String reason) {
        Entry entry = entry(stepNumber);
        entry.doneAt = doneAt;
        entry.doneStatus = status;
        entry.doneReason = reason;
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
     * @return the steps, in step order.
     */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }
}
