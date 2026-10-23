package ca.ualberta.odobot.trajectory;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.StepLedger;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TrajectoryWriterTest {

    static final String EVAL_ID = "7|OdoBotNL|0b1c";
    static final String STEM = "7-OdoBotNL-0b1c";

    @TempDir
    Path folder;

    private OnlineEventProcessor processor;
    private StepLedger ledger;

    /**
     * Step 1 clicks Save, which causes a click, two DOM effects and a POST. Step 2 types into the Name field.
     */
    @BeforeEach
    void runATask() {
        processor = new OnlineEventProcessor();
        processor.setUnchartedTimelineEnabled(true);
        processor.setTrajectoryCapture(true);
        ledger = new StepLedger();

        processor.process(startObservation(png(400, 300, Color.WHITE)));                       //0
        TimelineEntity start = processor.unchartedTimeline().get(0);

        ledger.emitted(1, "uncharted_step", trace(1, start, new JsonObject().put("action", "left_click")
                .put("x", 140).put("y", 65).put("screenshotWidth", 400).put("screenshotHeight", 300)), null);
        ledger.sent(1, "exec-1", 2_000, processor.lastEventIndex());
        processor.process(click(2_010, "clicked", png(400, 300, Color.BLUE)));                 //1
        processor.process(domEffect(2_020, "add", "/html/body/div[2]", "Saved", "saved"));    //2
        processor.process(domEffect(2_030, "remove", "/html/body/div[3]", "Spinner", "done"));//3
        processor.process(networkEvent(2_040, "POST", "http://shop.test/admin/catalog/product/save/")); //4
        ledger.done(1, 2_050, "ok", null);
        Observation afterClick = new Observation(Observation.Trigger.UNCHARTED_ACTION, null, BASE_URI,
                Screenshot.fromBase64(png(400, 300, Color.GRAY)), 3_000, 1, "exec-1");
        processor.addUnchartedObservation(afterClick);
        ledger.observed(1, afterClick, 3_000, processor.lastEventIndex());

        ledger.emitted(2, "uncharted_step", trace(2, afterClick, new JsonObject().put("action", "type").put("text", "Shirt")), null);
        ledger.sent(2, "exec-2", 4_000, processor.lastEventIndex());
        processor.process(input(4_010, "S", "typing"));                                        //5
        processor.process(input(4_020, "Shirt", "typed"));                                     //6
        Observation afterTyping = new Observation(Observation.Trigger.UNCHARTED_ACTION, null, BASE_URI,
                Screenshot.fromBase64(png(400, 300, Color.GREEN)), 5_000, 2, "exec-2");
        processor.addUnchartedObservation(afterTyping);
        ledger.observed(2, afterTyping, 5_000, processor.lastEventIndex());
    }

    static AgentTrace trace(int step, TimelineEntity input, JsonObject action) {
        return new AgentTrace(step, input, "<think>\nStep " + step + " because the form needs it.\n</think>\n\nAction: go",
                "Step " + step + " because the form needs it.", "Action: go", "Do step " + step, action,
                List.of("pyautogui.click(140, 65)"), List.of(), "NONE", null,
                new JsonArray().add(new JsonObject().put("role", "user")), "gen-" + step, 1000L, 80L, 12L,
                "DeepInfra", "reasoning", List.of("reasoning.text"), null, 400, 300, 400, 300, 1_500L + step);
    }

    static TrajectoryWriter.TaskRun run(String evalId) {
        JsonObject task = new JsonObject().put("id", "0b1c").put("_evalId", evalId).put("task", "Rename the shirt")
                .put("environment", new JsonObject().put("auth", new JsonObject().put("sites", new JsonObject()
                        .put("shopping_admin", new JsonObject().put("password", "admin1234")
                                .put("storageState", new JsonObject().put("cookies", new JsonArray().add("secret")))))));
        return new TrajectoryWriter.TaskRun("exp-1", evalId, task, "UNCHARTED", "ODO_BOT_NL", "qwen/qwen3.8-27b",
                new JsonObject().put("llm", new JsonObject().put("model", "qwen/qwen3.8-27b")), true, "abc123", "0.1+deadbeef",
                1_000, 6_000L, "completed", null, BASE_URI, "Shirt", false, 7,
                new JsonObject().put("result", "PASS").put("score", 1.0),
                new JsonObject().put("result", "PASS").put("agentAnswer", "Shirt").put("evalTypes", new JsonArray().add("url_match"))
                        .put("judgeModel", "gpt-4-1106-preview"));
    }

    @Test
    void writesTopLevelEntitiesOfBothTimelines() throws Exception {
        StagedTrajectory staged = TrajectoryWriter.stage(run(EVAL_ID), processor, ledger, folder);

        Map<String, JsonObject> rows = staged.entities().stream().map(o -> (JsonObject) o)
                .collect(Collectors.toMap(r -> r.getString("timeline") + ":" + r.getInteger("position"), r -> r));
        assertEquals(List.of("Observation", "ClickEvent", "Effect", "NetworkEvent", "DataEntry"),
                List.of("charted:0", "charted:1", "charted:2", "charted:3", "charted:4").stream().map(k -> rows.get(k).getString("kind")).toList());
        assertEquals(List.of("Observation", "Observation", "Observation"),
                List.of("uncharted:0", "uncharted:1", "uncharted:2").stream().map(k -> rows.get(k).getString("kind")).toList());

        JsonObject click = rows.get("charted:1");
        assertEquals("BTN_CLICK", click.getString("event_name"));
        assertEquals(1, click.getInteger("first_seq"));
        assertEquals("/html/body/div/button", click.getString("xpath"));
        assertEquals("browser", click.getString("clock"));
        assertEquals("screenshots/exp-1/" + STEM + "/charted-1.png", click.getString("screenshot_path"));
        assertEquals("screenshots/exp-1/" + STEM + "/charted-1-crop.png", click.getString("crop_path"));
        assertEquals(new JsonObject().put("x", 92).put("y", 42).put("width", 96).put("height", 46), click.getJsonObject("crop_box"));
        assertEquals("Save", click.getJsonObject("details").getString("text"));
        assertEquals(page("clicked"), staged.doms().getString(click.getString("dom_sha256")));

        JsonObject effect = rows.get("charted:2");
        assertEquals(2, effect.getInteger("first_seq"));
        assertEquals(3, effect.getInteger("last_seq"));
        assertEquals(2, effect.getJsonObject("details").getInteger("domEffectCount"));
        assertEquals("/html/body/div[2]", effect.getJsonObject("details").getJsonArray("netVisible").getJsonObject(0).getString("xpath"));
        assertEquals(page("done"), staged.doms().getString(effect.getString("dom_sha256")));

        JsonObject network = rows.get("charted:3").getJsonObject("details");
        assertEquals("[redacted]", network.getJsonObject("requestHeaders").getString("Cookie"));
        assertEquals("[redacted]", network.getJsonObject("responseHeaders").getString("Set-Cookie"));
        assertEquals(BASE_URI, network.getJsonObject("requestHeaders").getString("Referer"));

        JsonObject entry = rows.get("charted:4");
        assertEquals("Shirt", entry.getJsonObject("details").getString("enteredData"));
        assertEquals(5, entry.getInteger("first_seq"));
        assertEquals(6, entry.getInteger("last_seq"));

        //The start observation is on both timelines, and its screenshot is written once.
        assertEquals(rows.get("charted:0").getString("screenshot_path"), rows.get("uncharted:0").getString("screenshot_path"));
        assertEquals("server", rows.get("uncharted:1").getString("clock"));
        assertEquals(1, rows.get("uncharted:1").getJsonObject("details").getInteger("stepNumber"));

        for (String file : staged.files()) {
            assertTrue(Files.isRegularFile(folder.resolve(file)), file);
        }
        assertTrue(staged.files().containsAll(List.of("charted-0.png", "charted-1.png", "charted-1-crop.png", "uncharted-1.png",
                "uncharted-2.png", "step-1-action.png")));
        assertTrue(Files.isRegularFile(folder.resolve(TrajectoryStore.STAGED_FILE)));
    }

    @Test
    void stepsLinkToTheirObservationsAndTheEntitiesTheyCaused() throws Exception {
        StagedTrajectory staged = TrajectoryWriter.stage(run(EVAL_ID), processor, ledger, folder);

        JsonObject step1 = staged.steps().getJsonObject(0);
        assertEquals("uncharted:0", step1.getString("input_observation_key"));
        assertEquals("uncharted:1", step1.getString("result_observation_key"));
        assertEquals("Step 1 because the form needs it.", step1.getString("reasoning"));
        assertEquals(0, step1.getInteger("seq_at_send"));
        assertEquals(4, step1.getInteger("seq_at_observation"));
        assertEquals("exec-1", step1.getString("instruction_id"));
        assertEquals("DeepInfra", step1.getString("provider"));
        assertTrue(step1.getBoolean("reasoning_complete"));
        assertEquals("screenshots/exp-1/" + STEM + "/step-1-action.png", step1.getString("action_crop_path"));

        Map<String, List<String>> links = staged.links().stream().map(o -> (JsonObject) o).collect(Collectors.groupingBy(
                l -> l.getInteger("step_number") + "/" + l.getString("method"),
                Collectors.mapping(l -> l.getString("entity_key"), Collectors.toList())));
        assertEquals(List.of("charted:1", "charted:2", "charted:3"), links.get("1/seq_window").stream().sorted().toList());
        assertEquals(List.of("charted:1"), links.get("1/spatial"), "the click point lies in the Save button's box");
        assertEquals(List.of("charted:4"), links.get("2/seq_window"));
        assertNull(links.get("2/spatial"), "typing is not a pointer action");
    }

    @Test
    void anEntitySpanningTwoStepsLinksToBothWithItsShareOfEvents() throws Exception {
        //A DOM effect of the second step's page joins the first step's Effect only if the timeline's last entity is still
        //that Effect: here the typing happens on a fresh processor where an Effect spans both windows.
        OnlineEventProcessor spanning = new OnlineEventProcessor();
        StepLedger steps = new StepLedger();
        steps.emitted(1, "uncharted_step", trace(1, null, new JsonObject().put("action", "wait")), null);
        steps.sent(1, "e1", 0, spanning.lastEventIndex());
        spanning.process(domEffect(1_000, "add", "/html/body/a", "a", "1"));  //0
        spanning.process(domEffect(1_010, "add", "/html/body/b", "b", "2"));  //1
        steps.observed(1, null, 1_020, spanning.lastEventIndex());
        steps.emitted(2, "uncharted_step", trace(2, null, new JsonObject().put("action", "wait")), null);
        steps.sent(2, "e2", 1_030, spanning.lastEventIndex());
        spanning.process(domEffect(1_040, "add", "/html/body/c", "c", "3"));  //2

        StagedTrajectory staged = TrajectoryWriter.build(run(EVAL_ID), spanning, steps, folder);

        Map<Integer, Double> confidence = staged.links().stream().map(o -> (JsonObject) o)
                .collect(Collectors.toMap(l -> l.getInteger("step_number"), l -> l.getDouble("confidence")));
        assertEquals(2.0 / 3, confidence.get(1), 1e-9);
        assertEquals(1.0 / 3, confidence.get(2), 1e-9);
    }

    @Test
    void theTrajectoryCarriesTheTaskAndScoreWithoutSessions() throws Exception {
        JsonObject trajectory = TrajectoryWriter.stage(run(EVAL_ID), processor, ledger, folder).trajectory();

        assertEquals(EVAL_ID, trajectory.getString("eval_id"));
        assertEquals("0b1c", trajectory.getString("task_id"));
        assertEquals(7, trajectory.getInteger("webarena_task_id"));
        assertEquals("Rename the shirt", trajectory.getString("task_description"));
        JsonObject site = trajectory.getJsonObject("task_definition").getJsonObject("environment").getJsonObject("auth")
                .getJsonObject("sites").getJsonObject("shopping_admin");
        assertFalse(site.containsKey("storageState"), "session cookies are removed");
        assertEquals("admin1234", site.getString("password"), "the public WebArena login is part of the task");
        assertEquals("PASS", trajectory.getString("eval_result"));
        assertEquals(1.0, trajectory.getDouble("score"));
        assertEquals("Shirt", trajectory.getString("agent_answer"));
        assertEquals("gpt-4-1106-preview", trajectory.getString("judge_model"));
        assertEquals(true, trajectory.getBoolean("reasoning_required"));
        assertNull(trajectory.getString("build_error"));
    }

    @Test
    void aTaskWithoutTimelinesStagesItsTrajectoryRow() throws Exception {
        StagedTrajectory staged = TrajectoryWriter.stage(run(EVAL_ID), null, null, folder);

        assertTrue(staged.entities().isEmpty());
        assertEquals(EVAL_ID, staged.trajectory().getString("eval_id"));
        assertTrue(Files.isRegularFile(folder.resolve(TrajectoryStore.STAGED_FILE)));
    }

    @Test
    void theFallbackKeepsWhatCanBeRead() {
        StagedTrajectory fallback = TrajectoryWriter.fallback(run(EVAL_ID), processor, ledger, new IllegalStateException("boom"));

        assertEquals("java.lang.IllegalStateException: boom", fallback.trajectory().getString("build_error"));
        assertEquals(8, fallback.entities().size());
        assertEquals("Step 1 because the form needs it.", fallback.steps().getJsonObject(0).getString("reasoning"));
    }

    @Test
    void cropsAreClampedToTheImage() {
        var image = new java.awt.image.BufferedImage(100, 50, java.awt.image.BufferedImage.TYPE_INT_RGB);
        assertEquals(new JsonObject().put("x", 0).put("y", 0).put("width", 20).put("height", 18),
                TrajectoryWriter.clamp(image, -10, -2, 30, 20));
        assertNull(TrajectoryWriter.clamp(image, 200, 10, 30, 20), "outside the image");
        assertNull(TrajectoryWriter.clamp(image, 10, 10, 0, 20), "empty");
    }
}
