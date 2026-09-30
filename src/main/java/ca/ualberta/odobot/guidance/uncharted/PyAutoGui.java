package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.instructions.uncharted.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders uncharted instructions as the pyautogui code the Python agent produces for them. The parser uses it
 * to reproduce the Python fallback descriptions ("Performing click action"). The trajectory log records it so
 * runs can be compared with OSWorld trajectories. A scroll at a point renders as {@code pyautogui.scroll(n, x=.., y=..)};
 * Python ignores the coordinate.
 */
final class PyAutoGui {

    private PyAutoGui() {
    }

    /**
     * @param action the step's action, or null.
     */
    static List<String> codes(UnchartedInstruction action, ParsedStep.Terminal terminal) {
        List<String> codes = new ArrayList<>();
        if (action != null) {
            codes.addAll(codes(action));
        }
        if (terminal != ParsedStep.Terminal.NONE) {
            codes.add(terminal.name());
        }
        return codes;
    }

    static List<String> codes(UnchartedInstruction action) {
        return switch (action) {
            case LeftClick a -> List.of(click("click", a));
            case DoubleClick a -> List.of(click("doubleClick", a));
            case TripleClick a -> List.of(click("tripleClick", a));
            case TypeText a -> type(a.text);
            case KeyPress a -> List.of("pyautogui.%s(%s)".formatted(
                    a.isChord() ? "hotkey" : "press",
                    a.keys.stream().map(PyJson::dumps).collect(Collectors.joining(", "))));
            case Scroll a -> List.of(scroll("scroll", a));
            case HScroll a -> List.of(scroll("hscroll", a));
            case Wait a -> List.of("WAIT");
            default -> throw new IllegalArgumentException("Unknown uncharted instruction: " + action);
        };
    }

    private static String click(String method, PointerInstruction a) {
        return "pyautogui.%s(%d, %d)".formatted(method, a.x, a.y);
    }

    private static String scroll(String method, ScrollInstruction a) {
        return a.hasCoordinate()
                ? "pyautogui.%s(%d, x=%d, y=%d)".formatted(method, a.amount, a.x, a.y)
                : "pyautogui.%s(%d)".formatted(method, a.amount);
    }

    /**
     * Python types each line separately and presses Enter between lines.
     */
    private static List<String> type(String text) {
        if (!text.contains("\n")) {
            return List.of("pyautogui.typewrite(%s)".formatted(PyJson.dumps(text)));
        }
        List<String> codes = new ArrayList<>();
        String[] chunks = text.split("\n", -1);
        for (int i = 0; i < chunks.length; i++) {
            if (!chunks[i].isEmpty()) {
                codes.add("pyautogui.typewrite(%s)".formatted(PyJson.dumps(chunks[i])));
            }
            if (i < chunks.length - 1) {
                codes.add("pyautogui.press(\"enter\")");
            }
        }
        return codes;
    }
}
