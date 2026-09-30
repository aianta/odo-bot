package ca.ualberta.odobot.guidance.instructions.uncharted;

import io.vertx.core.json.JsonObject;

/**
 * Type text into the focused element. Line breaks are normalised to {@code \n}.
 *
 * <p>Synthetic key events insert no text, so OdoX edits the element itself:</p>
 * <ul>
 *     <li>An {@code input}, {@code textarea} or contenteditable element gets the text at the caret, replacing the
 *     selection (so a {@link TripleClick} followed by a TypeText replaces the field's text). OdoX uses
 *     {@code execCommand('insertText')}, falling back to the native value setter as {@code performInput} in
 *     guidance.js does, and dispatches a single {@code input} event for the whole text. One event per character
 *     would make LogUI record, and screenshot, every character.</li>
 *     <li>Each {@code \n} is handled as a {@link KeyPress} of {@code enter}: a line break in a textarea or
 *     contenteditable element, a form submission in an input.</li>
 *     <li>A focused {@code select} selects the first option whose visible text matches the text (case-insensitive,
 *     exact match before prefix match) and dispatches {@code input} and {@code change}. Synthetic clicks cannot
 *     open the native option list, so this is how the agent picks an option.</li>
 * </ul>
 */
public class TypeText extends UnchartedInstruction {

    public final String text;

    public TypeText(String text) {
        this.text = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    @Override
    public String action() {
        return "type";
    }

    @Override
    public JsonObject toJson() {
        return super.toJson().put("text", text);
    }
}
