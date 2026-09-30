package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.instructions.GiveUp;
import ca.ualberta.odobot.guidance.instructions.Instruction;
import ca.ualberta.odobot.guidance.instructions.TaskComplete;
import ca.ualberta.odobot.guidance.instructions.uncharted.LeftClick;
import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedStep;
import ca.ualberta.odobot.guidance.instructions.uncharted.Wait;
import ca.ualberta.odobot.guidance.uncharted.QwenModelClient.QwenCompletion;
import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class Qwen38AgentTest {

    private static final String CLICK = """
            <think>The button is in the middle.</think>
            Action: Click the Publish button.
            <tool_call>
            {"name": "computer_use", "arguments": {"action": "left_click", "coordinate": [500, 500]}}
            </tool_call>""";

    private static final String DONE = """
            Action: The course is published.
            <tool_call>
            {"name": "computer_use", "arguments": {"action": "terminate", "status": "success"}}
            </tool_call>""";

    @TempDir
    Path artifactDir;

    private Vertx vertx;
    private Context context;
    private FakeModel model;
    private Qwen38Agent agent;
    private final List<Instruction> emitted = new CopyOnWriteArrayList<>();
    private final List<Boolean> emittedOnEventLoop = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        model = new FakeModel();
        agent = new Qwen38Agent.Builder()
                .config(QwenAgentConfig.defaults())
                .modelClient(model)
                .task(new JsonObject().put("task", "Publish the course"))
                .artifactDir(artifactDir.toString())
                .evalId("eval-1")
                .build();
        agent.setInstructionConsumer(instruction -> {
            emitted.add(instruction);
            emittedOnEventLoop.add(Context.isOnEventLoopThread());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void oneStepPerObservationUntilTheModelTerminates() throws Exception {
        Promise<QwenCompletion> firstAnswer = Promise.promise();
        model.answers.add(firstAnswer);

        deliver(new ScreenshotEntity(1920, 1080));
        waitUntil(() -> model.calls.size() == 1);

        // Arrives while the model is answering: it predates the pending step, so it is ignored.
        deliver(new ScreenshotEntity(1920, 1080));
        context.runOnContext(v -> firstAnswer.complete(new QwenCompletion(CLICK, "req-1", null)));
        waitUntil(() -> emitted.size() == 1);

        UnchartedStep step = (UnchartedStep) emitted.get(0);
        assertEquals(1, step.step);
        assertEquals("Click the Publish button.", step.lowLevelInstruction);
        assertEquals(new LeftClick(960, 540, 1920, 1080), step.action);
        assertEquals(1, model.calls.size());

        // The next observation is the result of the step; no execution result is needed.
        Promise<QwenCompletion> emptyAnswer = Promise.promise();
        emptyAnswer.complete(new QwenCompletion("<think>nothing</think>", "req-2", null));
        model.answers.add(emptyAnswer);
        Promise<QwenCompletion> finalAnswer = Promise.promise();
        finalAnswer.complete(new QwenCompletion(DONE, "req-3", null));
        model.answers.add(finalAnswer);

        deliver(new ScreenshotEntity(1920, 1080));
        waitUntil(() -> emitted.size() == 2);

        // The empty answer executed nothing, so the agent predicted again on the same screenshot.
        assertEquals(3, model.calls.size());
        assertTrue(emitted.get(1) instanceof TaskComplete);
        assertTrue(emittedOnEventLoop.stream().allMatch(b -> b));
        assertEquals("req-3", agent.lastRequestId());
        assertEquals(List.of("Click the Publish button.", "", "The course is published."), agent.actions());

        // The second call replays step 1 with its answer, reasoning stripped.
        JsonArray secondCall = model.calls.get(1);
        assertEquals(List.of("system", "user", "assistant", "user"),
                secondCall.stream().map(m -> ((JsonObject) m).getString("role")).toList());
        String replayed = secondCall.getJsonObject(2).getJsonArray("content").getJsonObject(0).getString("text");
        assertFalse(replayed.contains("<think>"));
        assertTrue(replayed.startsWith("Action: Click the Publish button."));

        // After terminating, further entities are ignored.
        deliver(new ScreenshotEntity(1920, 1080));
        Thread.sleep(200);
        assertEquals(3, model.calls.size());

        waitUntil(() -> lines(artifactDir.resolve("eval-1-qwen38-trajectory.jsonl")) == 3);
        assertTrue(Files.exists(artifactDir.resolve("eval-1-qwen38-messages-step-0.json")));
        JsonObject firstLine = new JsonObject(Files.readAllLines(artifactDir.resolve("eval-1-qwen38-trajectory.jsonl")).get(0));
        assertEquals(List.of("pyautogui.click(960, 540)"), firstLine.getJsonArray("pyautogui").getList());
        assertEquals("req-1", firstLine.getString("request_id"));
    }

    @Test
    void unsupportedActionWaitsInsteadOfEndingTheTask() throws Exception {
        Promise<QwenCompletion> answer = Promise.promise();
        answer.complete(new QwenCompletion("""
                Action: Hover over the Settings menu.
                <tool_call>
                {"name": "computer_use", "arguments": {"action": "mouse_move", "coordinate": [500, 500]}}
                </tool_call>""", "req-1", null));
        model.answers.add(answer);

        deliver(new ScreenshotEntity(1920, 1080));
        waitUntil(() -> emitted.size() == 1);
        Thread.sleep(200);

        assertEquals(1, emitted.size(), emitted.toString());
        UnchartedStep step = (UnchartedStep) emitted.get(0);
        assertEquals(new Wait(null), step.action);
        String expected = "Hover over the Settings menu. [not executed: mouse_move is not available: there is no cursor, click elements directly]";
        assertEquals(expected, step.lowLevelInstruction);
        assertEquals(List.of(expected), agent.actions());
        assertEquals(1, model.calls.size());
    }

    @Test
    void modelFailureGivesUp() throws Exception {
        Promise<QwenCompletion> answer = Promise.promise();
        answer.fail(new RuntimeException("server down"));
        model.answers.add(answer);

        deliver(new ScreenshotEntity(800, 600));
        waitUntil(() -> emitted.size() == 1);

        assertTrue(emitted.get(0) instanceof GiveUp giveUp && giveUp.reason.contains("server down"), emitted.toString());
    }

    @Test
    void entitiesWithoutScreenshotsAreIgnored() throws Exception {
        deliver(new ScreenshotEntity(0, 0));
        Thread.sleep(200);
        assertTrue(model.calls.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    private void deliver(TimelineEntity entity) {
        context.runOnContext(v -> agent.observationHandler(entity));
    }

    private static long lines(Path path) {
        try {
            return Files.exists(path) ? Files.readAllLines(path).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for condition");
            }
            Thread.sleep(20);
        }
    }

    /**
     * Answers each call with the next queued promise.
     */
    private static class FakeModel implements QwenModelClient {
        final Queue<Promise<QwenCompletion>> answers = new ConcurrentLinkedQueue<>();
        final List<JsonArray> calls = new CopyOnWriteArrayList<>();

        @Override
        public Future<QwenCompletion> complete(Context context, JsonArray messages) {
            calls.add(messages);
            Promise<QwenCompletion> answer = answers.poll();
            return answer == null ? Future.failedFuture("no answer queued") : answer.future();
        }
    }

    /**
     * A timeline entity carrying a blank screenshot of the given size; a 0x0 size means no screenshot.
     */
    private static class ScreenshotEntity implements TimelineEntity {
        private final Screenshot screenshot;

        ScreenshotEntity(int width, int height) {
            this.screenshot = width == 0 ? null : new Screenshot(png(width, height));
        }

        private static String png(int width, int height) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
                return Base64.getEncoder().encodeToString(out.toByteArray());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public String symbol() {
            return "TEST";
        }

        @Override
        public JsonObject toJson() {
            return new JsonObject();
        }

        @Override
        public Screenshot getScreenshot() {
            return screenshot;
        }

        @Override
        public long timestamp() {
            return 0;
        }

        @Override
        public JsonObject getSemanticArtifacts() {
            return new JsonObject();
        }
    }
}
