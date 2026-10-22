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
 */
public record DispatchPolicy(long instructionPause, long followUpPause, boolean settleGate, long settleDelay, long settleMaxDelay,
                             LostInstruction onLost) {

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
     * Charted agents advance on the effects they observe, so an instruction waits for the DOM to settle first. An
     * instruction that missed its page goes to the next one, as it always has.
     */
    public static final DispatchPolicy CHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000, LostInstruction.REQUEUE_FOR_NEXT_PAGE);

    /**
     * The same pacing as charted runs, so uncharted results stay comparable with earlier runs. A step that missed its
     * page was chosen from a screenshot of that page, so it is dropped rather than run on another.
     */
    public static final DispatchPolicy UNCHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000, LostInstruction.UNCOUNT_AND_REOBSERVE);

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
        return new DispatchPolicy(instructionPause, followUpPause, settleGate, settleDelay, settleMaxDelay, onLost);
    }
}
