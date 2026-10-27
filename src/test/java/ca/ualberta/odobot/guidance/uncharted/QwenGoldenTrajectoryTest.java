package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays real Qwen3.8 responses recorded by OSWorld ({@code results_ep_smoke2}, {@code results_15x4}; 1920x1080
 * screen, relative coordinates) through the Java parser.
 *
 * <p>The web action space departs from Python for removed actions, scrolls, browser shortcuts and responses with several
 * actions (only the first runs); steps that use them are checked for not ending the task by accident.</p>
 */
class QwenGoldenTrajectoryTest {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /**
     * Actions whose Java handling differs from Python's.
     */
    private static final Set<String> CHANGED_ACTIONS = Set.of("mouse_move", "left_mouse_down", "left_mouse_up",
            "left_click_drag", "key_down", "key_up", "right_click", "middle_click", "scroll", "hscroll");

    /**
     * A rejected action must not end the task: only terminate, call_user or a response without a tool call do.
     */
    @Test
    void changedStepsOnlyEndWhenTheModelEndsTheTask() throws IOException {
        int[] processed = QwenImages.smartResize(HEIGHT, WIDTH, QwenImages.FACTOR, QwenImages.MIN_PIXELS,
                QwenImages.MAX_PIXELS, QwenImages.MAX_LONG_SIDE);

        List<String> ended = new ArrayList<>();
        for (JsonObject step : loadSteps()) {
            if (!usesChangedAction(step) || toolCalls(step).stream().anyMatch(call ->
                    "terminate".equals(call.get("action")) || "call_user".equals(call.get("action")))) {
                continue;
            }
            ParsedStep parsed = QwenResponseParser.parseResponse(step.getString("response"), CoordinateType.RELATIVE,
                    WIDTH, HEIGHT, processed[1], processed[0]);
            if (parsed.terminal() != ParsedStep.Terminal.NONE) {
                ended.add("%s step %d: %s".formatted(step.getString("source"), step.getInteger("step"), parsed));
            }
        }

        assertTrue(ended.isEmpty(), String.join("\n", ended));
    }

    private static boolean usesChangedAction(JsonObject step) {
        List<Map<String, Object>> calls = toolCalls(step);
        // Python runs every call up to a terminal one; Java runs only the first.
        long callsBeforeTerminal = calls.stream()
                .takeWhile(call -> !"terminate".equals(call.get("action")) && !"call_user".equals(call.get("action")))
                .count();
        if (callsBeforeTerminal > 1) {
            return true;
        }
        for (Map<String, Object> call : calls) {
            Object action = call.get("action");
            if (CHANGED_ACTIONS.contains(action)) {
                return true;
            }
            if ("key".equals(action) && QwenResponseParser.isReservedChord(
                    QwenResponseParser.parseKeys(call.getOrDefault("keys", new JsonArray()), true))) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> toolCalls(JsonObject step) {
        return QwenResponseParser.toolCallParams(QwenResponseParser.normalizeToolCalls(
                QwenResponseParser.stripReasoning(step.getString("response"))));
    }

    static List<JsonObject> loadSteps() throws IOException {
        List<JsonObject> steps = new ArrayList<>();
        try (InputStream in = QwenGoldenTrajectoryTest.class.getResourceAsStream("/qwen/qwen38_golden_steps.jsonl")) {
            assertNotNull(in, "golden fixture missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    steps.add(new JsonObject(line));
                }
            }
        }
        return steps;
    }
}
