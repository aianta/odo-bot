package ca.ualberta.odobot.guidance.instructions.uncharted;

/**
 * Scroll horizontally. Follows pyautogui.hscroll: a positive {@link #amount} scrolls right, a negative one scrolls
 * left.
 */
public class HScroll extends ScrollInstruction {

    public HScroll(int amount, Integer x, Integer y, int screenshotWidth, int screenshotHeight) {
        super(amount, x, y, screenshotWidth, screenshotHeight);
    }

    @Override
    public String action() {
        return "hscroll";
    }
}
