package ca.ualberta.odobot.guidance.instructions.uncharted;

/**
 * Select all the text of the element at (x, y). Qwen triple-clicks a field to replace its text with the next
 * {@link TypeText}.
 *
 * <p>OdoX dispatches the {@link LeftClick} sequence three times ({@code detail} 1 to 3) and focuses the target.
 * Synthetic clicks select nothing, so OdoX then selects the text itself: {@code select()} on an {@code input} or
 * {@code textarea}, otherwise a range over the contents of the nearest contenteditable element or the target.</p>
 */
public class TripleClick extends PointerInstruction {

    public TripleClick(int x, int y, int screenshotWidth, int screenshotHeight) {
        super(x, y, screenshotWidth, screenshotHeight);
    }

    @Override
    public String action() {
        return "triple_click";
    }
}
