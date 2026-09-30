package ca.ualberta.odobot.guidance.instructions.uncharted;

/**
 * Left-click the element at (x, y).
 *
 * <p>OdoX dispatches {@code pointerdown}, {@code mousedown}, {@code pointerup}, {@code mouseup} and a cancelable
 * {@code click} ({@code button} 0, {@code detail} 1). Synthetic clicks do not move focus, so OdoX also calls
 * {@code focus()} on the target or its nearest focusable ancestor; {@link TypeText} and {@link KeyPress} act on the
 * focused element.</p>
 */
public class LeftClick extends PointerInstruction {

    public LeftClick(int x, int y, int screenshotWidth, int screenshotHeight) {
        super(x, y, screenshotWidth, screenshotHeight);
    }

    @Override
    public String action() {
        return "left_click";
    }
}
