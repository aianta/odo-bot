package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.instructions.uncharted.*;
import ca.ualberta.odobot.guidance.uncharted.ParsedStep.Terminal;
import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QwenResponseParserTest {

    private static ParsedStep parse(String response) {
        return QwenResponseParser.parseResponse(response, CoordinateType.RELATIVE, 1920, 1080, 1920, 1088);
    }

    @Test
    void hermesJsonToolCallBecomesAClick() {
        ParsedStep step = parse("""
                <think>
                Maybe <tool_call>{"name": "computer_use", "arguments": {"action": "right_click", "coordinate": [1, 1]}}</tool_call>
                </think>
                Action: Click the Save button.
                <tool_call>
                {"name": "computer_use", "arguments": {"action": "left_click", "coordinate": [500, 500]}}
                </tool_call>""");

        assertEquals("Click the Save button.", step.lowLevelInstruction());
        assertEquals(Terminal.NONE, step.terminal());
        // Only the committed call counts, not the draft inside <think>.
        assertEquals(new LeftClick(960, 540, 1920, 1080), step.action());
        assertEquals(List.of("pyautogui.click(960, 540)"), step.pyAutoGuiCodes());
    }

    @Test
    void xmlToolCallIsParsedToo() {
        ParsedStep step = parse("""
                Action: Scroll down the page.
                <tool_call>
                <function=computer_use>
                <parameter=action>
                scroll
                </parameter>
                <parameter=pixels>
                -5
                </parameter>
                </function>
                </tool_call>""");

        assertEquals(new Scroll(-5, null, null, 1920, 1080), step.action());
    }

    @Test
    void nestedArgumentsAreUnwrapped() {
        ParsedStep step = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"arguments": {"action": "key", "keys": ["Ctrl", "B"]}}}</tool_call>""");

        assertEquals(new KeyPress(List.of("ctrl", "b")), step.action());
        assertEquals(List.of("pyautogui.hotkey(\"ctrl\", \"b\")"), step.pyAutoGuiCodes());
    }

    @Test
    void unclosedThinkIsDropped() {
        assertEquals("Answer", QwenResponseParser.stripReasoning("<think>a</think>\nAnswer\n<think>never closed <tool_call>"));
    }

    @Test
    void narrationReplacesGenericDescription() {
        ParsedStep step = parse("""
                I will open the course settings.
                <tool_call>{"name": "computer_use", "arguments": {"action": "left_click", "coordinate": [10, 20]}}</tool_call>""");

        assertEquals("I will open the course settings.", step.lowLevelInstruction());
    }

    @Test
    void genericDescriptionWhenThereIsNoNarration() {
        ParsedStep step = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"double_click\", \"coordinate\": [500, 500]}}</tool_call>");

        assertEquals("Performing doubleClick action", step.lowLevelInstruction());
        assertEquals(new DoubleClick(960, 540, 1920, 1080), step.action());
    }

    @Test
    void keyFormats() {
        assertEquals(List.of("ctrl", "c"), QwenResponseParser.parseKeys("ctrl+c", true));
        assertEquals(List.of("ctrl", "c"), QwenResponseParser.parseKeys("[\"ctrl\", \"c\"]", true));
        assertEquals(List.of("ctrl", "c"), QwenResponseParser.parseKeys("['ctrl', 'c']", true));
        assertEquals(List.of("Enter"), QwenResponseParser.parseKeys(" [Enter] ", false));
        assertEquals(List.of(), QwenResponseParser.parseKeys("", true));
    }

    @Test
    void multiLineTypeIsOneInstruction() {
        ParsedStep step = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "type", "text": "a\\r\\nb\\n"}}</tool_call>""");

        // As in Python, the JSON -> XML -> parameter round trip strips the trailing newline, so no final Enter.
        assertEquals(new TypeText("a\nb"), step.action());
        assertEquals(List.of("pyautogui.typewrite(\"a\")", "pyautogui.press(\"enter\")",
                "pyautogui.typewrite(\"b\")"), step.pyAutoGuiCodes());
    }

    @Test
    void terminateSuccessAndFailure() {
        ParsedStep done = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"terminate\", \"status\": \"success\"}}</tool_call>");
        assertEquals(Terminal.DONE, done.terminal());
        assertNull(done.action());
        assertEquals("Task completed", done.lowLevelInstruction());

        ParsedStep failed = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"terminate\", \"status\": \"failure\"}}</tool_call>");
        assertEquals(Terminal.FAIL, failed.terminal());
        assertEquals("Need user input", failed.lowLevelInstruction());
    }

    @Test
    void terminateCarriesTheAnswer() {
        ParsedStep answered = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"terminate\", \"status\": \"success\", \"answer\": \" Quest Lumaflex™ Band \"}}</tool_call>");
        assertEquals(Terminal.DONE, answered.terminal());
        assertEquals("Quest Lumaflex™ Band", answered.answer());

        ParsedStep xml = parse("""
                <tool_call>
                <function=computer_use>
                <parameter=action>terminate</parameter>
                <parameter=status>success</parameter>
                <parameter=answer>hollister</parameter>
                </function>
                </tool_call>""");
        assertEquals("hollister", xml.answer());

        assertNull(parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"terminate\", \"status\": \"success\"}}</tool_call>").answer());
        assertNull(parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"terminate\", \"status\": \"success\", \"answer\": \"  \"}}</tool_call>").answer());
        // Survives the narration that replaces the generic description.
        assertEquals("6", parse("""
                Action: Report the count.
                <tool_call>{"name": "computer_use", "arguments": {"action": "terminate", "status": "success", "answer": 6}}</tool_call>""").answer());
    }

    @Test
    void actionsAfterTerminateAreDropped() {
        ParsedStep step = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "wait"}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "terminate", "status": "success"}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "left_click", "coordinate": [1, 1]}}</tool_call>""");

        assertEquals(new Wait(null), step.action());
        assertEquals(Terminal.DONE, step.terminal());
    }

    @Test
    void noToolCallEndsTheTask() {
        assertEquals(Terminal.DONE, parse("The grades have been saved.").terminal());
        assertEquals(Terminal.FAIL, parse("This task is infeasible: there is no export option.").terminal());
    }

    @Test
    void callUserDependsOnInfeasibility() {
        String call = "<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"call_user\", \"text\": \"%s\"}}</tool_call>";
        assertEquals(Terminal.DONE, parse(call.formatted("Which course?")).terminal());
        assertEquals(Terminal.FAIL, parse(call.formatted("This is not possible.")).terminal());
    }

    @Test
    void emptyResponseProducesNothing() {
        ParsedStep step = parse("<think>thinking only</think>");
        assertEquals(Terminal.NONE, step.terminal());
        assertNull(step.action());
    }

    @Test
    void otherFunctionsAreIgnored() {
        ParsedStep step = parse("<tool_call>{\"name\": \"browser\", \"arguments\": {\"action\": \"left_click\"}}</tool_call>");
        assertNull(step.action());
        assertEquals(Terminal.DONE, step.terminal());
    }

    @Test
    void removedActionsAreUnsupportedAndDoNotEndTheTask() {
        ParsedStep step = parse("""
                Action: Drag the module to the top.
                <tool_call>{"name": "computer_use", "arguments": {"action": "left_mouse_down", "coordinate": [0, 0]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "mouse_move", "coordinate": [10, 10]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "left_click_drag", "coordinate": [999, 999]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "navigate", "text": "https://example.com"}}</tool_call>""");

        assertEquals(Terminal.NONE, step.terminal());
        assertNull(step.action());
        assertEquals(List.of(
                "left_mouse_down is not available: dragging is not supported",
                "mouse_move is not available: there is no cursor, click elements directly",
                "left_click_drag is not available: dragging is not supported",
                "navigate is not available"), step.unsupported());
        assertEquals("Drag the module to the top.", step.lowLevelInstruction());
        assertEquals(List.of(), step.pyAutoGuiCodes());
    }

    @Test
    void supportedActionsNextToUnsupportedOnesStillRun() {
        ParsedStep step = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "right_click", "coordinate": [500, 500]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "wait", "time": 2}}</tool_call>""");

        assertEquals(new Wait(2.0), step.action());
        assertEquals(List.of("right_click is not available: context menus cannot be opened"), step.unsupported());
        assertEquals("Waiting", step.lowLevelInstruction());
    }

    @Test
    void clicksNeedACoordinate() {
        ParsedStep step = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"left_click\"}}</tool_call>");

        assertNull(step.action());
        assertEquals(List.of("left_click needs a coordinate"), step.unsupported());
        assertEquals(Terminal.NONE, step.terminal());
        assertEquals("Performing action", step.lowLevelInstruction());
    }

    @Test
    void browserAndSystemShortcutsAreRejected() {
        for (List<String> keys : List.of(List.of("ctrl", "t"), List.of("ctrl", "shift", "s"), List.of("ctrl", "alt", "t"),
                List.of("alt", "tab"), List.of("alt", "f4"), List.of("f5"), List.of("win"), List.of("control", "l"),
                List.of("ctrl", "v"))) {
            assertTrue(QwenResponseParser.isReservedChord(keys), keys.toString());
        }
        for (List<String> keys : List.of(List.of("enter"), List.of("esc"), List.of("tab"), List.of("shift", "tab"),
                List.of("ctrl", "a"), List.of("ctrl", "b"), List.of("ctrl", "z"), List.of("pagedown"), List.of("f2"),
                List.of("delete"))) {
            assertFalse(QwenResponseParser.isReservedChord(keys), keys.toString());
        }

        ParsedStep step = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"key\", \"keys\": \"ctrl+l\"}}</tool_call>");
        assertNull(step.action());
        assertEquals(List.of("ctrl+l is a browser or system shortcut"), step.unsupported());
        assertEquals(Terminal.NONE, step.terminal());
    }

    @Test
    void scrollKeepsItsCoordinateAndAcceptsAmountAliases() {
        ParsedStep scroll = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "scroll", "coordinate": [500, 500], "scroll_amount": -10}}</tool_call>""");
        assertEquals(new Scroll(-10, 960, 540, 1920, 1080), scroll.action());
        assertEquals(List.of("pyautogui.scroll(-10, x=960, y=540)"), scroll.pyAutoGuiCodes());

        JsonObject json = scroll.action().toJson();
        assertEquals(-10, json.getInteger("amount"));
        assertEquals(960, json.getInteger("x"));
        assertEquals(1920, json.getInteger("screenshotWidth"));

        ParsedStep hscroll = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "hscroll", "amount": 3}}</tool_call>""");
        assertEquals(new HScroll(3, null, null, 1920, 1080), hscroll.action());
        assertEquals(List.of("pyautogui.hscroll(3)"), hscroll.pyAutoGuiCodes());

        ParsedStep zero = parse("""
                <tool_call>{"name": "computer_use", "arguments": {"action": "scroll", "coordinate": [500, 500], "text": "5"}}</tool_call>""");
        assertNull(zero.action());
        assertEquals(List.of("scroll needs a non-zero `pixels` amount"), zero.unsupported());
        assertEquals(Terminal.NONE, zero.terminal());
    }

    @Test
    void onlyTheFirstActionRuns() {
        ParsedStep step = parse("""
                Action: Type the title and save.
                <tool_call>{"name": "computer_use", "arguments": {"action": "mouse_move", "coordinate": [500, 500]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "type", "text": "Week 1"}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "key", "keys": ["enter"]}}</tool_call>
                <tool_call>{"name": "computer_use", "arguments": {"action": "terminate", "status": "success"}}</tool_call>""");

        assertEquals(new TypeText("Week 1"), step.action());
        assertEquals(List.of(
                "mouse_move is not available: there is no cursor, click elements directly",
                "key (only one action runs per step)"), step.unsupported());
        // A terminal call still ends the task after the action.
        assertEquals(Terminal.DONE, step.terminal());
        assertEquals(List.of("pyautogui.typewrite(\"Week 1\")", "DONE"), step.pyAutoGuiCodes());
    }

    @Test
    void screenshotBecomesAWait() {
        ParsedStep step = parse("<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"screenshot\"}}</tool_call>");

        assertEquals(new Wait(null), step.action());
        assertTrue(step.unsupported().isEmpty());
        assertEquals(Terminal.NONE, step.terminal());
    }

    @Test
    void theToolAdvertisesOnlyWebActions() {
        JsonObject tool = QwenPrompts.toolsDef(1920, 1088, CoordinateType.RELATIVE);
        JsonObject properties = tool.getJsonObject("function").getJsonObject("parameters").getJsonObject("properties");
        assertEquals(List.of("key", "type", "left_click", "double_click", "triple_click", "scroll", "hscroll", "wait",
                "terminate", "call_user"), properties.getJsonObject("action").getJsonArray("enum").getList());
        String description = tool.getJsonObject("function").getString("description")
                + properties.getJsonObject("action").getString("description");
        for (String removed : List.of("mouse_move", "left_click_drag", "key_down", "right_click", "middle_click", "screenshot`", "take screenshots", "move the cursor")) {
            assertFalse(description.contains(removed), removed);
        }
    }

    @Test
    void absoluteCoordinatesScaleFromProcessedSize() {
        ParsedStep step = QwenResponseParser.parseResponse(
                "<tool_call>{\"name\": \"computer_use\", \"arguments\": {\"action\": \"left_click\", \"coordinate\": [960, 544]}}</tool_call>",
                CoordinateType.ABSOLUTE, 1920, 1080, 1920, 1088);
        assertEquals(new LeftClick(960, 540, 1920, 1080), step.action());
    }

    @Test
    void instructionJson() {
        UnchartedStep step = new UnchartedStep(3, "Click", new LeftClick(1, 2, 1920, 1080));
        assertEquals("uncharted_step", step.toJson().getString("action"));
        assertEquals("left_click", step.toJson().getJsonObject("unchartedAction").getString("action"));
        assertEquals(1920, step.toJson().getJsonObject("unchartedAction").getInteger("screenshotWidth"));
    }
}
