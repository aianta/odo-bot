package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QwenImagesAndHistoryTest {

    @Test
    void smartResizeMatchesPython() {
        // Values computed with mm_agents/utils/qwen_vl_utils.smart_resize(h, w, factor=32, max_pixels=16*16*4*12800).
        assertArrayEquals(new int[]{1088, 1920}, resize(1080, 1920));
        assertArrayEquals(new int[]{768, 1376}, resize(768, 1366));
        // 2160 / 32 = 67.5 rounds half to even (68), as Python's round() does.
        assertArrayEquals(new int[]{2176, 3840}, resize(2160, 3840));
    }

    private static int[] resize(int h, int w) {
        return QwenImages.smartResize(h, w, QwenImages.FACTOR, QwenImages.MIN_PIXELS, QwenImages.MAX_PIXELS, QwenImages.MAX_LONG_SIDE);
    }

    @Test
    void processResizesAndEncodes() {
        QwenImages.ProcessedImage processed = QwenImages.process(new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB));
        assertEquals(1920, processed.width());
        assertEquals(1088, processed.height());
        assertTrue(processed.base64Png().startsWith("iVBORw0KGgo"), "PNG signature");
    }

    @Test
    void coordinateAdjustment() {
        assertEquals(new QwenImages.Point(960, 540), QwenImages.adjustCoordinates(500, 500, CoordinateType.RELATIVE, 1920, 1080, 1920, 1088));
        assertEquals(new QwenImages.Point(1920, 1080), QwenImages.adjustCoordinates(999, 999, CoordinateType.RELATIVE, 1920, 1080, 1920, 1088));
        assertEquals(new QwenImages.Point(100, 99), QwenImages.adjustCoordinates(100, 100, CoordinateType.ABSOLUTE, 1920, 1080, 1920, 1088));
    }

    @Test
    void foldingCollapsesInPairsOnceFiveImagesAreExceeded() {
        int k = 0;
        List<Integer> folded = new ArrayList<>();
        for (int total = 1; total <= 8; total++) {
            k = QwenHistory.updateFoldingState(total, k, 5, 2);
            folded.add(k);
        }
        assertEquals(List.of(0, 0, 0, 0, 0, 2, 2, 4), folded);
    }

    @Test
    void previousActions() {
        assertEquals("None", QwenHistory.previousActionsText(List.of("a", "b"), 1));
        assertEquals("Step 1: a\nStep 2: b", QwenHistory.previousActionsText(List.of("a", "b", "c"), 3));
    }

    @Test
    void messagesReplayRecentStepsAndCollapseOldScreenshots() {
        List<String> screenshots = List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8");
        List<String> responses = List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7");
        int total = 8;
        int start = Math.max(1, total - 5); // history_n = 5
        JsonArray messages = QwenHistory.buildMessages("SYS", "INSTR", screenshots, responses, start, total, 4, "COLLAPSED");

        // system + 6 user turns (steps 3..8) + 5 assistant turns (steps 3..7)
        assertEquals(12, messages.size());
        assertEquals("system", messages.getJsonObject(0).getString("role"));

        // Step 3 is the first replayed turn and is collapsed: instruction text only.
        JsonArray first = messages.getJsonObject(1).getJsonArray("content");
        assertEquals(1, first.size());
        assertEquals("INSTR", first.getJsonObject(0).getString("text"));
        assertEquals("r3", messages.getJsonObject(2).getJsonArray("content").getJsonObject(0).getString("text"));

        // Step 4 is collapsed: placeholder text wrapped in a tool response.
        JsonArray fourth = messages.getJsonObject(3).getJsonArray("content");
        assertEquals(List.of("<tool_response>\n", "COLLAPSED", "\n</tool_response>"),
                fourth.stream().map(p -> ((JsonObject) p).getString("text")).toList());

        // Step 8 carries its screenshot and has no answer yet.
        JsonObject last = messages.getJsonObject(11);
        assertEquals("user", last.getString("role"));
        assertEquals("data:image/png;base64,s8",
                last.getJsonArray("content").getJsonObject(1).getJsonObject("image_url").getString("url"));
    }

    @Test
    void dumpTruncatesImages() {
        String url = "data:image/png;base64," + "A".repeat(100);
        JsonArray messages = new JsonArray().add(QwenHistory.message("user", new JsonArray().add(QwenHistory.imagePart(url))));
        String dumped = QwenHistory.sanitizeForDump(messages).getJsonObject(0).getJsonArray("content")
                .getJsonObject(0).getJsonObject("image_url").getString("url");
        assertEquals(url.substring(0, 40) + "...<omitted>", dumped);
    }

    @Test
    void toolsDefIsSerialisedLikePython() {
        String json = PyJson.dumps(QwenPrompts.toolsDef(1920, 1088, CoordinateType.RELATIVE));
        assertTrue(json.startsWith("{\"type\": \"function\", \"function\": {\"name\": \"computer_use\", \"description\": \"Use a mouse"), json);
        assertTrue(json.contains("\"required\": [\"action\"]"));
        assertTrue(json.contains("The screen's resolution is 1000x1000."));
    }
}
