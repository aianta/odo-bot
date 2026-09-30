package ca.ualberta.odobot.guidance.instructions.uncharted;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Press keys. A single key is pressed and released. Several keys form a chord (hotkey): the last key is pressed
 * while the others are held as modifiers. Key names are lower-case pyautogui names, e.g. {@code ctrl}, {@code enter},
 * {@code pagedown}.
 *
 * <p>OdoX dispatches {@code keydown} and {@code keyup} to the focused element (or {@code document.body}), with
 * {@code key}, {@code code} and the modifier flags set, so the page's own keyboard handlers run. Synthetic key
 * events have no default action, so when {@code keydown} is not cancelled OdoX performs it for these keys:</p>
 * <ul>
 *     <li>{@code enter}/{@code return}: activate a focused button or link, submit the form of a focused input
 *     ({@code requestSubmit}), insert a line break in a textarea or contenteditable element.</li>
 *     <li>{@code tab}, {@code shift+tab}: move focus to the next or previous focusable element.</li>
 *     <li>{@code backspace}, {@code delete}: delete the selection, or the character before or after the caret,
 *     and dispatch {@code input}.</li>
 *     <li>{@code space}: activate a focused button, checkbox or radio button; otherwise insert a space in an
 *     editable element.</li>
 *     <li>{@code up}, {@code down}, {@code left}, {@code right}, {@code home}, {@code end}: move the caret in an
 *     editable element, change the selected option of a focused {@code select} (then dispatch {@code change}),
 *     otherwise scroll the page.</li>
 *     <li>{@code pageup}, {@code pagedown}: scroll by one viewport height.</li>
 *     <li>{@code ctrl+a}: select all the text of the focused editable element, otherwise of the page.</li>
 *     <li>{@code esc}: no default action; page handlers close their own dialogs and menus.</li>
 * </ul>
 * <p>Browser and operating-system shortcuts (new tab, address bar, save, reload, window switching...) are never
 * produced: the parser rejects them.</p>
 */
public class KeyPress extends UnchartedInstruction {

    public final List<String> keys;

    public KeyPress(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("KeyPress requires at least one key");
        }
        this.keys = List.copyOf(keys);
    }

    public boolean isChord() {
        return keys.size() > 1;
    }

    @Override
    public String action() {
        return "key";
    }

    @Override
    public JsonObject toJson() {
        return super.toJson().put("keys", new JsonArray(keys));
    }
}
