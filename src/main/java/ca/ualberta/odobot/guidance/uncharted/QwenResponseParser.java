package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.instructions.uncharted.*;
import ca.ualberta.odobot.guidance.uncharted.ParsedStep.Terminal;
import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;
import ca.ualberta.odobot.guidance.uncharted.QwenImages.Point;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Qwen3.8 response into uncharted instructions. Ported from {@code mm_agents/qwen/parser.py},
 * {@code parse_internal_response} in {@code mm_agents/qwen/actions.py}, and the response handling in
 * {@code mm_agents/qwen3_8.py}.
 *
 * <p>Response handling (reasoning, tool-call formats, keys, coordinates, termination) follows Python. The action
 * switch does not: it produces the web action space OdoX can execute in a page (see {@link QwenPrompts}).</p>
 * <ul>
 *     <li>Actions OdoX cannot perform ({@code mouse_move}, dragging, held keys, right and middle clicks), unknown
 *     actions, clicks without a coordinate, zero scrolls and browser or system shortcuts are not executed. They
 *     are reported in {@link ParsedStep#unsupported()} and do not end the task.</li>
 *     <li>Only the first executable tool call of a response runs. The ones after it are reported as unsupported.</li>
 *     <li>{@code scroll}/{@code hscroll} keep their coordinate and also read the amount from {@code scroll_amount}
 *     or {@code amount}.</li>
 *     <li>A multi-line {@code type} is one {@link TypeText} (Python splits it around Enter presses).</li>
 * </ul>
 */
public final class QwenResponseParser {

    private static final int FLAGS = Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", FLAGS);
    private static final Pattern UNCLOSED_THINK = Pattern.compile("<think>.*\\z", FLAGS);
    private static final Pattern JSON_TOOL_CALL = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", FLAGS);
    private static final Pattern TOOL_CALL = Pattern.compile("<tool_call>(.*?)</tool_call>", FLAGS);
    private static final Pattern FUNCTION = Pattern.compile("<function=([^>]+)>", FLAGS);
    private static final Pattern PARAMETER = Pattern.compile("<parameter=([^>]+)>\\s*(.*?)\\s*</parameter>", FLAGS);
    private static final Pattern KEY_SEPARATOR = Pattern.compile("\\s*\\+\\s*", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Set<String> FAILURE_STATUSES = Set.of("fail", "failed", "failure", "error", "infeasible");

    /**
     * OSWorld actions that OdoX cannot execute in a web page, with the reason shown to the model.
     */
    private static final Map<String, String> REMOVED_ACTIONS = Map.of(
            "mouse_move", ": there is no cursor, click elements directly",
            "left_mouse_down", ": dragging is not supported",
            "left_mouse_up", ": dragging is not supported",
            "left_click_drag", ": dragging is not supported",
            "key_down", ": use `key` with the whole combination",
            "key_up", ": use `key` with the whole combination",
            "right_click", ": context menus cannot be opened",
            "middle_click", ": new tabs cannot be opened"
    );

    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("control", "ctrl"), Map.entry("ctrlleft", "ctrl"), Map.entry("ctrlright", "ctrl"),
            Map.entry("altleft", "alt"), Map.entry("altright", "alt"), Map.entry("option", "alt"),
            Map.entry("shiftleft", "shift"), Map.entry("shiftright", "shift")
    );

    private static final Set<String> MODIFIER_KEYS = Set.of("ctrl", "alt", "shift");

    private static final Set<String> OS_KEYS = Set.of("win", "winleft", "winright", "super", "meta", "cmd", "command",
            "fn", "printscreen", "prtsc", "prtscr", "prntscrn");

    private static final Set<String> BROWSER_FUNCTION_KEYS = Set.of("f1", "f3", "f5", "f6", "f7", "f10", "f11", "f12");

    private static final Set<String> BROWSER_CTRL_KEYS = Set.of("t", "w", "n", "l", "r", "s", "p", "f", "o", "d", "h",
            "j", "q", "u", "g", "e", "k", "tab", "pageup", "pagedown", "+", "-", "=", "0", "plus", "minus",
            "c", "v", "x");

    private static final Set<String> BROWSER_ALT_KEYS = Set.of("tab", "f4", "left", "right", "home");

    private static final List<String> INFEASIBLE_LITERALS = List.of(
            "not possible",
            "impossible",
            "not feasible",
            "cannot be completed",
            "can't be completed",
            "cannot be done",
            "cannot complete",
            "can't complete",
            "unable to complete",
            "cannot do this task",
            "can't do this task",
            "cannot complete this task as described",
            "cannot be completed as specified",
            "can't be completed as specified",
            "not available in your country",
            "not available",
            "unavailable",
            "not supported",
            "does not support",
            "doesn't support",
            "cannot natively",
            "does not have a built-in",
            "doesn't have a built-in",
            "does not include",
            "is not among the natively built-in",
            "will fall back to english",
            "requires the official",
            "no bluetooth found",
            "plug in a dongle",
            "folder is empty",
            "downloads folder is empty",
            "do not have the credentials",
            "don't have the credentials",
            "do not have the account credentials",
            "don't have the account credentials",
            "need the user's google account credentials",
            "requires a language pack extension",
            "requires email verification",
            "requires a sign-up",
            "requires sign-up",
            "requires google account credentials",
            "requires a google account",
            "sign in to the google account",
            "drm-protected",
            "drm protection",
            "cannot directly play",
            "no legitimate way",
            "requires a plugin",
            "requires an extension",
            "requires extension",
            "requires plugin",
            "requires a valid account",
            "requires purchase",
            "requires a purchased",
            "no valid account",
            "hidden audio",
            "could you clarify"
    );

    private static final List<Pattern> INFEASIBLE_PATTERNS = List.of(
            Pattern.compile("\\bthere is no [a-z0-9 _-]+\\b", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\bno [a-z0-9 _-]+ in [a-z0-9 _-]+ list\\b", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\brequires? (an? )?(extension|plugin|account|credentials|hardware|language pack)\\b", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\bneed(?:s)? (an? )?(extension|plugin|account|credentials|hardware|language pack)\\b", Pattern.UNICODE_CHARACTER_CLASS),
            // The Python source writes "\\b" inside a raw string, so the regex looks for a literal backslash
            // followed by "b" and practically never matches. Kept as is.
            Pattern.compile("\\b(without|no) (extensions?|plugins?|terminal|ffmpeg|other apps?).{0,120}\\\\b(cannot|can't|not possible|not feasible)\\b", Pattern.UNICODE_CHARACTER_CLASS)
    );

    private static final Object NOT_PARSED = new Object();

    private QwenResponseParser() {
    }

    /**
     * Port of {@code Qwen38Agent.parse_response}: drop the reasoning, rewrite Hermes JSON tool calls into the XML
     * dialect, parse, and recover the step description from the narration when the model left out
     * {@code Action:}.
     */
    public static ParsedStep parseResponse(String response, CoordinateType coordinateType,
                                           int originalWidth, int originalHeight,
                                           int processedWidth, int processedHeight) {
        String normalized = normalizeToolCalls(stripReasoning(response));
        ParsedStep step = parseInternalResponse(normalized, coordinateType,
                originalWidth, originalHeight, processedWidth, processedHeight);
        String low = step.lowLevelInstruction();
        if (low.isEmpty() || low.startsWith("Performing ")) {
            String narration = narrationBeforeToolCall(normalized);
            if (!narration.isEmpty()) {
                step = step.withLowLevelInstruction(narration);
            }
        }
        return step;
    }

    /**
     * Drop {@code <think>} blocks, including an unclosed trailing one, so drafts in the reasoning are never
     * parsed as actions.
     */
    public static String stripReasoning(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        String text = THINK_BLOCK.matcher(response).replaceAll("");
        text = UNCLOSED_THINK.matcher(text).replaceAll("");
        return text.strip();
    }

    /**
     * Rewrite Hermes JSON tool calls into the {@code <function=...>} XML dialect.
     */
    public static String normalizeToolCalls(String response) {
        Matcher matcher = JSON_TOOL_CALL.matcher(response == null ? "" : response);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(rewriteJsonToolCall(match.group(1), match.group(0))));
    }

    private static String rewriteJsonToolCall(String json, String original) {
        Object payload;
        try {
            payload = Json.decodeValue(json);
        } catch (DecodeException e) {
            return original;
        }
        if (!(payload instanceof JsonObject call)) {
            return original;
        }

        Object name = call.getValue("name");
        if (!truthy(name) || !(call.getValue("arguments") instanceof JsonObject arguments)) {
            return original;
        }
        if (arguments.getValue("arguments") instanceof JsonObject nested) {
            arguments = nested;
        }

        StringJoiner params = new StringJoiner("\n");
        arguments.forEach(entry -> params.add(xmlParameter(entry.getKey(), entry.getValue())));
        return "<tool_call>\n<function=" + pyStr(name) + ">\n" + params + "\n</function>\n</tool_call>";
    }

    private static String xmlParameter(String name, Object value) {
        String rendered;
        if (value instanceof JsonArray || value instanceof JsonObject || value instanceof List || value instanceof Map) {
            rendered = PyJson.dumps(value);
        } else if (value instanceof Boolean bool) {
            rendered = bool ? "true" : "false";
        } else {
            rendered = value == null ? "" : String.valueOf(value);
        }
        return "<parameter=" + name + ">\n" + rendered + "\n</parameter>";
    }

    /**
     * Recover the step description when the model narrates the step but leaves out the {@code Action:} prefix.
     */
    public static String narrationBeforeToolCall(String response) {
        int end = response.indexOf("<tool_call>");
        String head = end < 0 ? response : response.substring(0, end);
        String candidate = null;
        for (String line : head.split("\\R")) {
            if (!line.strip().isEmpty()) {
                candidate = line.strip();
            }
        }
        if (candidate == null) {
            return "";
        }
        if (candidate.toLowerCase(Locale.ROOT).startsWith("action:")) {
            candidate = candidate.substring(candidate.indexOf(':') + 1).strip();
        }
        return truncateCodePoints(candidate, 300);
    }

    /**
     * Port of {@code parse_internal_response}.
     */
    public static ParsedStep parseInternalResponse(String response, CoordinateType coordinateType,
                                                   int originalWidth, int originalHeight,
                                                   int processedWidth, int processedHeight) {
        if (response == null || response.isBlank()) {
            return new ParsedStep("", null, Terminal.NONE);
        }

        boolean infeasible = looksInfeasibleResponse(response);
        String lowLevelInstruction = extractActionLine(response);
        StepBuilder step = new StepBuilder(coordinateType, originalWidth, originalHeight, processedWidth, processedHeight, infeasible);

        for (Map<String, Object> params : toolCallParams(response)) {
            if (step.terminal != Terminal.NONE) {
                // OSWorld stops executing a step's actions once one reports done.
                break;
            }
            step.process(params);
        }

        // A response with nothing to execute ends the task, unless the model asked for something unsupported.
        if (step.action == null && step.terminal == Terminal.NONE && step.unsupported.isEmpty()) {
            step.terminal = infeasible ? Terminal.FAIL : Terminal.DONE;
        }

        if (lowLevelInstruction.isEmpty()) {
            List<String> codes = PyAutoGui.codes(step.action, step.terminal);
            String firstCode = codes.isEmpty() ? "" : codes.get(0);
            lowLevelInstruction = "FAIL".equals(firstCode) ? "Need user input" : instructionFromFirstCode(firstCode);
        }

        return new ParsedStep(lowLevelInstruction, step.action, step.terminal, step.unsupported);
    }

    /**
     * Accumulates the instructions of one response.
     */
    private static final class StepBuilder {
        private final CoordinateType coordinateType;
        private final int originalWidth;
        private final int originalHeight;
        private final int processedWidth;
        private final int processedHeight;
        private final boolean infeasible;

        private UnchartedInstruction action;
        private final List<String> unsupported = new ArrayList<>();
        private Terminal terminal = Terminal.NONE;

        private StepBuilder(CoordinateType coordinateType, int originalWidth, int originalHeight,
                            int processedWidth, int processedHeight, boolean infeasible) {
            this.coordinateType = coordinateType;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.processedWidth = processedWidth;
            this.processedHeight = processedHeight;
            this.infeasible = infeasible;
        }

        /**
         * A step executes one action, the first one: every step then yields one observation of its result.
         */
        private void add(UnchartedInstruction instruction) {
            if (this.action != null) {
                unsupported.add(instruction.action() + " (only one action runs per step)");
                return;
            }
            this.action = instruction;
        }

        private void process(Map<String, Object> params) {
            if (!(params.get("action") instanceof String action) || action.isEmpty()) {
                return;
            }

            double[] coordinate = parseCoordinate(params.get("coordinate"));
            Point point = coordinate == null ? null : QwenImages.adjustCoordinates(coordinate[0], coordinate[1],
                    coordinateType, originalWidth, originalHeight, processedWidth, processedHeight);
            Integer x = point == null ? null : point.x();
            Integer y = point == null ? null : point.y();
            int w = originalWidth;
            int h = originalHeight;

            switch (action) {
                case "left_click", "double_click", "triple_click" -> {
                    if (point == null) {
                        unsupported.add(action + " needs a coordinate");
                    } else {
                        add(switch (action) {
                            case "left_click" -> new LeftClick(x, y, w, h);
                            case "double_click" -> new DoubleClick(x, y, w, h);
                            default -> new TripleClick(x, y, w, h);
                        });
                    }
                }
                case "type" -> {
                    Object text = params.get("text");
                    add(new TypeText(text == null ? "" : pyStr(text)));
                }
                case "key" -> {
                    List<String> keys = parseKeys(params.getOrDefault("keys", new JsonArray()), true);
                    if (isReservedChord(keys)) {
                        unsupported.add(String.join("+", keys) + " is a browser or system shortcut");
                    } else if (!keys.isEmpty()) {
                        add(new KeyPress(keys));
                    }
                }
                case "scroll", "hscroll" -> {
                    int amount = (int) parseNumber(scrollAmount(params), 0);
                    if (amount == 0) {
                        unsupported.add(action + " needs a non-zero `pixels` amount");
                    } else {
                        add("scroll".equals(action)
                                ? new Scroll(amount, x, y, w, h)
                                : new HScroll(amount, x, y, w, h));
                    }
                }
                case "wait" -> add(new Wait(params.containsKey("time") ? parseNumberOrNull(params.get("time")) : null));
                case "terminate" -> terminal = terminationCode(params.getOrDefault("status", "success"));
                case "call_user" -> terminal = infeasible ? Terminal.FAIL : Terminal.DONE;
                // Observations come from the harness; a new one follows every step.
                case "screenshot" -> add(new Wait(null));
                default -> unsupported.add(action + " is not available"
                        + REMOVED_ACTIONS.getOrDefault(action, ""));
            }
        }
    }

    /**
     * The scroll amount. The model sometimes names it {@code scroll_amount} or {@code amount} instead of
     * {@code pixels}.
     */
    private static Object scrollAmount(Map<String, Object> params) {
        for (String name : List.of("pixels", "scroll_amount", "amount")) {
            if (params.containsKey(name)) {
                return params.get(name);
            }
        }
        return 0;
    }

    /**
     * True for key combinations that only the browser or the operating system acts on, and for clipboard
     * shortcuts. Synthetic key events cannot trigger them, so OdoX would silently do nothing.
     */
    static boolean isReservedChord(List<String> keys) {
        Set<String> modifiers = new HashSet<>();
        List<String> others = new ArrayList<>();
        for (String key : keys) {
            String normalized = KEY_ALIASES.getOrDefault(key, key);
            if (OS_KEYS.contains(normalized)) {
                return true;
            }
            if (MODIFIER_KEYS.contains(normalized)) {
                modifiers.add(normalized);
            } else {
                others.add(normalized);
            }
        }
        if (others.stream().anyMatch(BROWSER_FUNCTION_KEYS::contains)) {
            return true;
        }
        if (modifiers.contains("ctrl") && modifiers.contains("alt")) {
            return true;
        }
        if (modifiers.contains("ctrl") && others.stream().anyMatch(BROWSER_CTRL_KEYS::contains)) {
            return true;
        }
        return modifiers.contains("alt") && others.stream().anyMatch(BROWSER_ALT_KEYS::contains);
    }

    static Terminal terminationCode(Object status) {
        String normalized = (truthy(status) ? pyStr(status) : "success").strip().toLowerCase(Locale.ROOT);
        return FAILURE_STATUSES.contains(normalized) ? Terminal.FAIL : Terminal.DONE;
    }

    static String instructionFromFirstCode(String firstCode) {
        if ("DONE".equals(firstCode)) {
            return "Task completed";
        }
        if ("WAIT".equals(firstCode)) {
            return "Waiting";
        }
        int dot = firstCode.indexOf('.');
        if (dot >= 0) {
            String method = firstCode.substring(dot + 1);
            int paren = method.indexOf('(');
            return "Performing " + (paren >= 0 ? method.substring(0, paren) : method) + " action";
        }
        return "Performing action";
    }

    /**
     * Parameters of every {@code computer_use} tool call in the response, in order. Calls to other functions,
     * and calls without parameters, are skipped.
     */
    static List<Map<String, Object>> toolCallParams(String response) {
        List<Map<String, Object>> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALL.matcher(response);
        while (matcher.find()) {
            Map<String, Object> params = parseXmlToolCall(matcher.group(1));
            if (params != null && !params.isEmpty()) {
                calls.add(params);
            }
        }
        return calls;
    }

    static Map<String, Object> parseXmlToolCall(String xmlContent) {
        Matcher function = FUNCTION.matcher(xmlContent);
        if (!function.find() || !"computer_use".equals(function.group(1))) {
            return null;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Matcher parameter = PARAMETER.matcher(xmlContent);
        while (parameter.find()) {
            String name = parameter.group(1);
            String value = parameter.group(2).strip();
            if (value.startsWith("[") || value.startsWith("{")) {
                Object decoded = tryDecodeJson(value);
                if (decoded != NOT_PARSED) {
                    params.put(name, decoded);
                    continue;
                }
            }
            params.put(name, value);
        }
        return params;
    }

    /**
     * Accepts a JSON list, a JSON or Python-literal list in a string, or a {@code +}-joined string such as
     * {@code "ctrl+c"}. Returns the individual key names.
     */
    public static List<String> parseKeys(Object rawKeys, boolean lowercase) {
        if (rawKeys instanceof String text) {
            Object decoded = tryDecodeJson(text);
            if (decoded == NOT_PARSED) {
                decoded = tryDecodePythonLiteral(text);
            }
            if (decoded != NOT_PARSED) {
                rawKeys = decoded;
            }
        }

        List<String> keys = new ArrayList<>();
        flattenKeys(rawKeys, keys);
        if (lowercase) {
            keys.replaceAll(key -> key.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static void flattenKeys(Object keys, List<String> out) {
        if (keys == null) {
            return;
        }
        if (keys instanceof JsonArray array) {
            array.forEach(item -> flattenKeys(item, out));
            return;
        }
        if (keys instanceof List<?> list) {
            list.forEach(item -> flattenKeys(item, out));
            return;
        }
        for (String part : KEY_SEPARATOR.split(pyStr(keys).strip(), -1)) {
            String cleaned = cleanKeyToken(part);
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            }
        }
    }

    private static String cleanKeyToken(String key) {
        String token = key.strip();
        token = stripChars(token, " \t\r\n[](){}\"'", true, true);
        token = stripChars(token, " \t\r\n]", false, true);
        token = stripChars(token, " \t\r\n[", true, false);
        return token.strip();
    }

    /**
     * @return {x, y} in model space, or null when the value is not a list of at least two numbers.
     */
    public static double[] parseCoordinate(Object rawCoordinate) {
        if (rawCoordinate instanceof String text) {
            rawCoordinate = tryDecodeJson(text);
        }
        if (rawCoordinate instanceof JsonArray array && array.size() >= 2) {
            Double x = numberOrNull(array.getValue(0));
            Double y = numberOrNull(array.getValue(1));
            // Python would fail on non-numeric coordinates when scaling them; treat them as absent instead.
            return x == null || y == null ? null : new double[]{x, y};
        }
        return null;
    }

    public static double parseNumber(Object rawValue, double defaultValue) {
        Double number = parseNumberOrNull(rawValue);
        return number == null ? defaultValue : number;
    }

    private static Double parseNumberOrNull(Object rawValue) {
        if (rawValue instanceof Boolean bool) {
            return bool ? 1.0 : 0.0;
        }
        return numberOrNull(rawValue);
    }

    private static Double numberOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The text after the first {@code Action:} line, or "".
     */
    public static String extractActionLine(String response) {
        for (String line : response.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.toLowerCase(Locale.ROOT).startsWith("action:")) {
                return stripped.substring(stripped.indexOf(':') + 1).strip();
            }
        }
        return "";
    }

    /**
     * Heuristic for responses that declare the task infeasible. Decides between DONE and FAIL when the model
     * ends the task without {@code terminate}.
     */
    public static boolean looksInfeasibleResponse(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        if (lowered.contains("infeasible")) {
            return true;
        }
        for (String literal : INFEASIBLE_LITERALS) {
            if (lowered.contains(literal)) {
                return true;
            }
        }
        for (Pattern pattern : INFEASIBLE_PATTERNS) {
            if (pattern.matcher(lowered).find()) {
                return true;
            }
        }
        return false;
    }

    private static Object tryDecodeJson(String text) {
        try {
            return Json.decodeValue(text);
        } catch (DecodeException e) {
            return NOT_PARSED;
        }
    }

    /**
     * Rough stand-in for {@code ast.literal_eval}, enough for single-quoted lists such as {@code ['ctrl', 'c']}.
     */
    private static Object tryDecodePythonLiteral(String text) {
        String stripped = text.strip();
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            stripped = "[" + stripped.substring(1, stripped.length() - 1) + "]";
        }
        if (!(stripped.startsWith("[") || stripped.startsWith("'"))) {
            return NOT_PARSED;
        }
        return tryDecodeJson(stripped.replace('\'', '"'));
    }

    /**
     * Python truthiness for decoded JSON values.
     */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof JsonArray array) {
            return !array.isEmpty();
        }
        if (value instanceof JsonObject object) {
            return !object.isEmpty();
        }
        return true;
    }

    /**
     * Python's str() for decoded JSON scalars.
     */
    private static String pyStr(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "True" : "False";
        }
        return String.valueOf(value);
    }

    private static String stripChars(String text, String chars, boolean leading, boolean trailing) {
        int start = 0;
        int end = text.length();
        while (leading && start < end && chars.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (trailing && end > start && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    private static String truncateCodePoints(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
