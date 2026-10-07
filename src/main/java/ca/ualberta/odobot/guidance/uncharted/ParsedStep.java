package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedInstruction;

import java.util.List;

/**
 * The outcome of parsing one model response.
 *
 * @param lowLevelInstruction the model's one-line description of the step, kept for the "Previous actions" prompt.
 * @param action              the GUI action to execute, or null. Only the first executable tool call of a response is
 *                            executed; the calls after it are reported in {@code unsupported}. Calls the model listed after
 *                            a terminal call are dropped, as OSWorld stops executing once a step reports done.
 * @param terminal            whether the step ends the task, and how.
 * @param unsupported         why each tool call that asked for something OdoX cannot do was not executed, e.g.
 *                            "mouse_move is not available: there is no cursor, click elements directly".
 * @param answer              the answer the model reported with {@code terminate}, or null if it reported none.
 */
public record ParsedStep(String lowLevelInstruction, UnchartedInstruction action, Terminal terminal,
                         List<String> unsupported, String answer) {

    public enum Terminal {
        /**
         * The task continues.
         */
        NONE,
        /**
         * The model reports the task as complete (OSWorld's DONE).
         */
        DONE,
        /**
         * The model reports the task as failed or infeasible (OSWorld's FAIL).
         */
        FAIL
    }

    public ParsedStep {
        unsupported = List.copyOf(unsupported);
    }

    public ParsedStep(String lowLevelInstruction, UnchartedInstruction action, Terminal terminal,
                      List<String> unsupported) {
        this(lowLevelInstruction, action, terminal, unsupported, null);
    }

    public ParsedStep(String lowLevelInstruction, UnchartedInstruction action, Terminal terminal) {
        this(lowLevelInstruction, action, terminal, List.of());
    }

    public ParsedStep withLowLevelInstruction(String lowLevelInstruction) {
        return new ParsedStep(lowLevelInstruction, action, terminal, unsupported, answer);
    }

    /**
     * @return the pyautogui code OSWorld would execute for this step, e.g. {@code pyautogui.click(960, 540)}.
     */
    public List<String> pyAutoGuiCodes() {
        return PyAutoGui.codes(action, terminal);
    }
}
