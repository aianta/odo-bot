package ca.ualberta.odobot.guidance.instructions.uncharted;

import io.vertx.core.json.JsonObject;

/**
 * Scroll the page, or the part of it at a point.
 *
 * <p>{@link #amount} is in mouse-wheel notches, as in pyautogui, never zero. OdoX converts notches to CSS pixels
 * with a tunable factor (about 50 px per notch).</p>
 *
 * <p>{@link #x} and {@link #y} are optional screenshot coordinates, scaled to CSS pixels as for
 * {@link PointerInstruction}. When they are null OdoX uses the centre of the viewport. OdoX scrolls the nearest
 * scrollable ancestor of the element at that point on the instruction's axis, otherwise the window, with
 * {@code scrollBy}.</p>
 */
public abstract class ScrollInstruction extends UnchartedInstruction {

    public final int amount;
    public final Integer x;
    public final Integer y;
    public final int screenshotWidth;
    public final int screenshotHeight;

    protected ScrollInstruction(int amount, Integer x, Integer y, int screenshotWidth, int screenshotHeight) {
        if (amount == 0) {
            throw new IllegalArgumentException("A scroll amount of 0 does nothing");
        }
        if ((x == null) != (y == null)) {
            throw new IllegalArgumentException("x and y must both be set or both be null");
        }
        this.amount = amount;
        this.x = x;
        this.y = y;
        this.screenshotWidth = screenshotWidth;
        this.screenshotHeight = screenshotHeight;
    }

    public boolean hasCoordinate() {
        return x != null;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = super.toJson()
                .put("amount", amount)
                .put("screenshotWidth", screenshotWidth)
                .put("screenshotHeight", screenshotHeight);
        if (hasCoordinate()) {
            json.put("x", x).put("y", y);
        }
        return json;
    }
}
