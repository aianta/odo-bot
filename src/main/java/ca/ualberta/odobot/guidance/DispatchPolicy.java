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
 */
public record DispatchPolicy(long instructionPause, long followUpPause, boolean settleGate, long settleDelay, long settleMaxDelay) {

    /**
     * Charted agents advance on the effects they observe, so an instruction waits for the DOM to settle first.
     */
    public static final DispatchPolicy CHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000);

    /**
     * The same pacing as charted runs, so uncharted results stay comparable with earlier runs.
     */
    public static final DispatchPolicy UNCHARTED = new DispatchPolicy(3000, 1000, true, 5000, 30000);

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
        return new DispatchPolicy(instructionPause, followUpPause, settleGate, settleDelay, settleMaxDelay);
    }
}
