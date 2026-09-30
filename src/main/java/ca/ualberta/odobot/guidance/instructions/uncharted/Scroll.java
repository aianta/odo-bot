package ca.ualberta.odobot.guidance.instructions.uncharted;

/**
 * Scroll vertically. Follows pyautogui.scroll: a positive {@link #amount} scrolls up, a negative one scrolls down.
 */
public class Scroll extends ScrollInstruction {

    public Scroll(int amount, Integer x, Integer y, int screenshotWidth, int screenshotHeight) {
        super(amount, x, y, screenshotWidth, screenshotHeight);
    }

    @Override
    public String action() {
        return "scroll";
    }
}
