package ca.ualberta.odobot.guidance;

/**
 * How {@link InstructionDispatcher} paces the instructions of a task. Each {@link ExecutionMode} has its own, see
 * {@link #forMode}.
 *
 * @param instructionPause how long, in milliseconds, an instruction waits before it is sent.
 * @param followUpPause how long a follow-up of a multi-step instruction waits before it is sent.
 * @param settleGate whether an instruction is also held until the DOM has settled, see
 *                   {@link InstructionDispatcher#onPageActivity}.
 * @param settleDelay how long, in milliseconds, the DOM must go without a change before a held instruction is sent.
 * @param settleMaxDelay the longest an instruction is held for the DOM to settle. On a page that keeps changing, each
 *                       change would otherwise postpone the next instruction again, until the task times out.
 * @param onLost what to do with an instruction OdoX could not deliver, because the page it was addressed to had gone.
 * @param awaitDone whether an instruction waits until OdoX has reported the one before it done (EXECUTE_DONE). A policy
 *                  that waits for it treats an instruction without one as lost, see
 *                  {@link LostInstruction#UNCOUNT_AND_REOBSERVE}; one that doesn't only logs it.
 * @param doneTimeout how long, in milliseconds, OdoX may take to report an instruction done, on top of the duration of
 *                    a {@code wait} action.
 */
public record DispatchPolicy(long instructionPause, long followUpPause, boolean settleGate, long settleDelay, long settleMaxDelay,
                             LostInstruction onLost, boolean awaitDone, long doneTimeout) {

    /**
     * What happens to an instruction that never reached the page it was addressed to.
     */
    public enum LostInstruction {
        /**
         * Send it again, to the next page that is ready.
         */
        REQUEUE_FOR_NEXT_PAGE,
        /**
         * Drop it, take it off the agent's step count, and observe the page again, so the agent decides anew from what
         * is on the screen now.
         */
        UNCOUNT_AND_REOBSERVE
    }

    /**
     * Charted agents advance on the effects they observe (e.g. the network request a click is known to cause), so an
     * instruction waits for the DOM to settle first. EXECUTE_DONE is only a sanity check for them: the next instruction
     * never waits for it. An instruction that missed its page goes to the next one, as it always has.
     */
    public static final DispatchPolicy CHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000,
            LostInstruction.REQUEUE_FOR_NEXT_PAGE, false, 30000);

    /**
     * The same pacing as charted runs, so uncharted results stay comparable with earlier runs. A step is observed once
     * OdoX reports it done, and the next one waits for that. A step that missed its page, or never got reported done,
     * was chosen from a screenshot of a page that may be gone, so it is dropped rather than run on another.
     */
    public static final DispatchPolicy UNCHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000,
            LostInstruction.UNCOUNT_AND_REOBSERVE, true, 30000);

    /**
     * @return the policy for tasks executed in the given mode.
     */
    public static DispatchPolicy forMode(ExecutionMode mode){
        return mode == ExecutionMode.UNCHARTED? UNCHARTED : CHARTED;
    }

    /**
     * @return this policy with other pauses.
     */
    public DispatchPolicy withPauses(long instructionPause, long followUpPause){
        return new DispatchPolicy(instructionPause, followUpPause, settleGate, settleDelay, settleMaxDelay, onLost, awaitDone, doneTimeout);
    }

    /**
     * @return this policy with another time limit for EXECUTE_DONE.
     */
    public DispatchPolicy withDoneTimeout(long doneTimeout){
        return new DispatchPolicy(instructionPause, followUpPause, settleGate, settleDelay, settleMaxDelay, onLost, awaitDone, doneTimeout);
    }
}
