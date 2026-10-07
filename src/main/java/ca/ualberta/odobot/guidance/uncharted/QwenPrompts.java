package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Prompts for {@link Qwen38Agent}, ported from {@code mm_agents/qwen/prompts.py} and {@code build_system_prompt} in
 * {@code mm_agents/qwen3_8.py}.
 *
 * <p>The Python prompts describe an Ubuntu desktop driven by pyautogui. Here the agent acts on a web page through
 * OdoX, which can only dispatch synthetic DOM events, and its observations come from the harness. The system prompt,
 * the tool description and the action list are therefore web-specific: {@code mouse_move}, dragging, held keys,
 * right and middle clicks and {@code screenshot} are gone, clicks need a coordinate and scrolls may take one. The
 * tool's name and parameter names, the response format and the rules are unchanged, as Qwen was trained on them.</p>
 */
public final class QwenPrompts {

    /**
     * Adapted from INTERNAL_ACTION_DESCRIPTION_PROMPT to the actions OdoX can perform on a web page.
     */
    static final String ACTION_DESCRIPTION = """
            * `key`: Press a key or a key combination on the focused element; the keys of a combination are pressed \
            in order and released in reverse order.
            * `type`: Type a string of text into the focused element, replacing any selected text. Click the field \
            first. To choose an option of a drop-down list, click the list, then type the option's text.
            * `left_click`: Click the left mouse button on the element at the (x, y) pixel coordinate.
            * `double_click`: Double-click the left mouse button on the element at the (x, y) pixel coordinate.
            * `triple_click`: Select all the text of the field at the (x, y) pixel coordinate, e.g. to replace it with `type`.
            * `scroll`: Scroll the page, or the scrollable area at the (x, y) pixel coordinate, vertically.
            * `hscroll`: Scroll the page, or the scrollable area at the (x, y) pixel coordinate, horizontally.
            * `wait`: Wait specified seconds for the change to happen.
            * `terminate`: Terminate the current task and report its completion status, and the answer if the task \
            asks for information.
            * `call_user`: Ask user for information or confirmation.""";

    static final String[] ACTIONS = {
            "key", "type", "left_click", "double_click", "triple_click", "scroll", "hscroll", "wait", "terminate",
            "call_user"
    };

    private QwenPrompts() {
    }

    /**
     * Adapted from build_description_prompt.
     */
    static String descriptionPrompt(int processedWidth, int processedHeight, CoordinateType coordinateType) {
        String resolution = coordinateType == CoordinateType.ABSOLUTE
                ? "* The screen's resolution is %dx%d.".formatted(processedWidth, processedHeight)
                : "* The screen's resolution is 1000x1000.";
        return """
                Use a mouse and keyboard to interact with a web application in a browser.
                * This is an interface to a web application displayed in a browser viewport. The screenshots show \
                only the page content. You do not have access to the browser's address bar, tabs, bookmarks, menus, \
                or developer tools, or to a terminal, and browser or system shortcuts do nothing. Navigate by \
                interacting with the page itself.
                * After each action you receive a new screenshot of the viewport. Some pages may take time to load or \
                process actions, so you may need to wait to see the results of your actions.
                %s
                * There is no cursor to move: every click targets the element at the coordinate you give. Consult the \
                latest screenshot to determine the coordinates of the element before clicking it.
                * If you tried clicking on a link or button but it failed to respond, even after waiting, try \
                adjusting the coordinates so that they fall squarely on the element that you want to click.
                * Make sure to click any buttons, links, icons, etc in the center of the element. Don't click boxes \
                on their edges unless asked.""".formatted(resolution);
    }

    /**
     * Port of build_internal_tools_def. Keys are in the same order as the Python dict.
     */
    public static JsonObject toolsDef(int processedWidth, int processedHeight, CoordinateType coordinateType) {
        JsonObject properties = new JsonObject()
                .put("action", new JsonObject()
                        .put("type", "string")
                        .put("description", ACTION_DESCRIPTION)
                        .put("enum", new JsonArray(java.util.List.of(ACTIONS))))
                .put("keys", new JsonObject()
                        .put("type", "array")
                        .put("description", "Required only by `action=key`. Supported: `enter`, `tab`, `esc`, `backspace`, `delete`, `space`, the arrow keys, `home`, `end`, `pageup`, `pagedown`, `ctrl+a`, `shift` combinations, and the keyboard shortcuts of the page itself. Browser and system shortcuts (e.g. `ctrl+t`, `ctrl+l`, `alt+tab`) and clipboard shortcuts (`ctrl+c`, `ctrl+v`, `ctrl+x`) are not available."))
                .put("text", new JsonObject()
                        .put("type", "string")
                        .put("description", "Required only by `action=type` and `action=call_user`."))
                .put("coordinate", new JsonObject()
                        .put("type", "array")
                        .put("description", "(x, y) coordinates. Required by `action=left_click`, `action=double_click` and `action=triple_click`, optional for `action=scroll` and `action=hscroll`."))
                .put("pixels", new JsonObject()
                        .put("type", "number")
                        .put("description", "Scroll amount in mouse-wheel notches, non-zero: positive scrolls up or right, negative scrolls down or left. Required only by `action=scroll` or `action=hscroll`."))
                .put("time", new JsonObject()
                        .put("type", "number")
                        .put("description", "Seconds to wait. Required only by `action=wait`."))
                .put("status", new JsonObject()
                        .put("type", "string")
                        .put("description", "Task status for terminate.")
                        .put("enum", new JsonArray().add("success").add("failure")))
                .put("answer", new JsonObject()
                        .put("type", "string")
                        .put("description", "Used only by `action=terminate`. If the task asks for information, the answer, stated as concisely as possible."));

        return new JsonObject()
                .put("type", "function")
                .put("function", new JsonObject()
                        .put("name", "computer_use")
                        .put("description", descriptionPrompt(processedWidth, processedHeight, coordinateType))
                        .put("parameters", new JsonObject()
                                .put("type", "object")
                                .put("required", new JsonArray().add("action"))
                                .put("properties", properties)));
    }

    /**
     * Adapted from build_system_prompt in qwen3_8.py, in Qwen3.8's native Hermes tool-calling format.
     */
    public static String systemPrompt(JsonObject toolsDef, String collapseText) {
        return """
                You are an autonomous GUI agent operating a web application in a web browser. You perceive the \
                page only through screenshots of the browser viewport and act only through the tool below.

                # Tools

                You may call one function per step to assist with the user query.

                You are provided with function signatures within <tools></tools> XML tags:
                <tools>
                %s
                </tools>

                For each function call, return a json object with function name and arguments \
                within <tool_call></tool_call> XML tags:
                <tool_call>
                {"name": <function-name>, "arguments": <args-json-object>}
                </tool_call>

                # Response format

                For a normal UI interaction step, reply with exactly two parts in this order:
                1) A line that begins with the literal prefix `Action:` followed by one short
                   imperative sentence describing what to do in the UI.
                2) A single <tool_call>...</tool_call> block.

                Example of a well-formed step:
                Action: Click the Submit button below the form.
                <tool_call>
                {"name": "computer_use", "arguments": {"action": "left_click", "coordinate": [72, 410]}}
                </tool_call>

                Rules:
                - Emit exactly one tool call per step. Never output anything after it.
                - Coordinates must address the element's centre, not its edge.
                - Use `wait` when a page is still loading rather than clicking blindly.
                - Use `terminate` with status success when the task is complete, or status \
                failure when it cannot be completed. If the task asks for information, give it in `answer`.
                - Use `call_user` only when you genuinely need information you cannot obtain \
                from the screen.
                - If the task is infeasible, say so explicitly and terminate with status failure.
                - Older screenshots are replaced by this placeholder text: %s""".formatted(PyJson.dumps(toolsDef), collapseText);
    }

    /**
     * build_instruction_prompt, verbatim.
     */
    public static String instructionPrompt(String instruction, String previousActions) {
        return """

                Please generate the next move according to the UI screenshot, instruction and previous actions.

                Instruction: %s

                Previous actions:
                %s""".formatted(instruction, previousActions);
    }
}
