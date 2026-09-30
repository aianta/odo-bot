package ca.ualberta.odobot.guidance.instructions.uncharted;

/**
 * Double-click the element at (x, y).
 *
 * <p>OdoX dispatches the {@link LeftClick} sequence twice ({@code detail} 1, then 2), then {@code dblclick}, and
 * focuses the target. The browser's native word selection is not emulated.</p>
 */
public class DoubleClick extends PointerInstruction {

    public DoubleClick(int x, int y, int screenshotWidth, int screenshotHeight) {
        super(x, y, screenshotWidth, screenshotHeight);
    }

    @Override
    public String action() {
        return "double_click";
    }
}
