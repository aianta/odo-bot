package ca.ualberta.odobot.guidance;

import ca.ualberta.odobot.semanticflow.model.Observation;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class UnchartedStepObserverTest {

    private static final String LOCATION = "https://canvas.example.com/courses/1/modules";

    private Vertx vertx;
    private Context context;
    private UnchartedStepObserver observer;
    private final List<Long> requests = new CopyOnWriteArrayList<>();
    private final List<Observation> observations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        observer = new UnchartedStepObserver(vertx, ()->requests.add(System.currentTimeMillis()), observations::add);
        observer.quietPeriod = 150;
        observer.maxWait = 1000;
        observer.replyTimeout = 250;
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void theScreenshotIsTakenOnceThePageIsQuiet() throws Exception {
        long sent = System.currentTimeMillis();
        run(()->observer.observe(step(1, click())));

        waitUntil(()->requests.size() == 1);
        assertTrue(requests.get(0) - sent >= 150, "requested before the quiet period elapsed");

        run(()->observer.onScreenshot(png(), LOCATION));
        waitUntil(()->observations.size() == 1);

        Observation observation = observations.get(0);
        assertEquals(Observation.Trigger.UNCHARTED_ACTION, observation.getTrigger());
        assertEquals(click(), observation.getTriggerDetails());
        assertEquals(LOCATION, observation.getUserLocation());
        assertNotNull(observation.getScreenshot().getImage());

        Thread.sleep(400);
        assertEquals(1, requests.size(), "a step is observed once");
    }

    @Test
    void eventsPostponeTheScreenshot() throws Exception {
        long sent = System.currentTimeMillis();
        run(()->observer.observe(step(1, click())));
        for (int i = 0; i < 6; i++) {
            Thread.sleep(80);
            run(observer::onEvent);
        }
        long lastEvent = System.currentTimeMillis();

        waitUntil(()->requests.size() == 1);
        assertTrue(requests.get(0) - lastEvent >= 100, "requested while the page was still changing");
        assertTrue(requests.get(0) - sent < 1000);
    }

    @Test
    void aBusyPageIsScreenshottedAfterTheMaximumWait() throws Exception {
        long sent = System.currentTimeMillis();
        run(()->observer.observe(step(1, click())));
        while (requests.isEmpty() && System.currentTimeMillis() - sent < 3000) {
            Thread.sleep(50);
            run(observer::onEvent);
        }

        assertEquals(1, requests.size());
        long elapsed = requests.get(0) - sent;
        assertTrue(elapsed >= 950 && elapsed < 1500, "requested after " + elapsed + " ms");
    }

    @Test
    void aWaitIsNotObservedBeforeItsDuration() throws Exception {
        long sent = System.currentTimeMillis();
        run(()->observer.observe(step(1, new JsonObject().put("action", "wait").put("seconds", 0.6))));

        waitUntil(()->requests.size() == 1);
        assertTrue(requests.get(0) - sent >= 600, "requested during the wait");
    }

    @Test
    void anUnansweredRequestIsRetriedThenAbandoned() throws Exception {
        observer.maxScreenshotRequests = 2;
        run(()->observer.observe(step(1, click())));

        waitUntil(()->requests.size() == 2);
        Thread.sleep(600);
        assertEquals(2, requests.size());

        run(()->observer.onScreenshot(png(), LOCATION));
        Thread.sleep(100);
        assertTrue(observations.isEmpty(), "a late reply belongs to no step");
    }

    @Test
    void aScreenshotNobodyAskedForIsIgnored() throws Exception {
        run(()->observer.onScreenshot(png(), LOCATION));
        run(()->observer.observe(step(1, click())));
        run(()->observer.onScreenshot(png(), LOCATION));
        Thread.sleep(50);
        assertTrue(observations.isEmpty());
    }

    /**
     * Runs the action on the observer's context, as its timers do, and waits for it.
     */
    private void run(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v->{
            action.run();
            done.complete(null);
        });
        done.get(5, TimeUnit.SECONDS);
    }

    private static JsonObject click() {
        return new JsonObject().put("action", "left_click").put("x", 960).put("y", 540)
                .put("screenshotWidth", 1920).put("screenshotHeight", 1080);
    }

    private static JsonObject step(int number, JsonObject action) {
        return new JsonObject().put("type", "EXECUTE").put("action", "uncharted_step").put("step", number)
                .put("lowLevelInstruction", "Do it").put("unchartedAction", action);
    }

    private static String png() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for condition");
            }
            Thread.sleep(10);
        }
    }
}
