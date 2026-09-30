package ca.ualberta.odobot.guidance;

/**
 * How OdoBot executes a task. Every mode builds the main (charted) online timeline from OdoX's events, which is what gets saved
 * and evaluated.
 */
public enum ExecutionMode {

    /**
     * The {@link ChartedAgent} follows paths through the navigation model, as in the CASCON 2026 evaluation.
     */
    CHARTED,

    /**
     * An {@link UnchartedAgent} (the Qwen3.8 agent) acts on screenshots. Only this mode builds the uncharted observation timeline
     * that feeds it: one observation when transmission starts, then one per step, see {@link UnchartedStepObserver}.
     */
    UNCHARTED,

    /**
     * Charted and uncharted agents sharing a task. Not implemented yet, see {@link HybridAgent}.
     */
    HYBRID
}
