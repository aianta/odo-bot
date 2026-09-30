package ca.ualberta.odobot.guidance.instructions.uncharted;

import io.vertx.core.json.JsonObject;

/**
 * A mouse action on the element at a point of the page.
 *
 * <p>{@link #x} and {@link #y} are pixel coordinates in the original screenshot the agent observed, whose size is
 * {@link #screenshotWidth} x {@link #screenshotHeight}. The screenshot shows the viewport only, usually at the device
 * pixel ratio, so OdoX maps a point to CSS pixels with {@code x * innerWidth / screenshotWidth} (and likewise for y).
 * A coordinate is always required: OdoX cannot move a cursor, so there is no "current pointer position".</p>
 *
 * <p>OdoX resolves the target with {@code document.elementFromPoint}, descending into same-origin iframes (e.g.
 * TinyMCE) and open shadow roots, and dispatches synthetic events to it with {@code clientX}/{@code clientY} set.</p>
 */
public abstract class PointerInstruction extends UnchartedInstruction {

    public final int x;
    public final int y;
    public final int screenshotWidth;
    public final int screenshotHeight;

    protected PointerInstruction(int x, int y, int screenshotWidth, int screenshotHeight) {
        this.x = x;
        this.y = y;
        this.screenshotWidth = screenshotWidth;
        this.screenshotHeight = screenshotHeight;
    }

    @Override
    public JsonObject toJson() {
        return super.toJson()
                .put("screenshotWidth", screenshotWidth)
                .put("screenshotHeight", screenshotHeight)
                .put("x", x)
                .put("y", y);
    }
}
