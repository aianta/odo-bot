package ca.ualberta.odobot.trajectory;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.StepLedger;
import ca.ualberta.odobot.semanticflow.model.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Builds a task's trajectory from what the harness holds once the task is over (both timelines, the step ledger and the
 * agent's thoughts), stages it, and leaves the commit to the {@link TrajectoryStore}.
 *
 * <p>Only top-level timeline entities are saved. A {@link DataEntry} or {@link Effect} is saved as one row that sums
 * up its input changes or DOM effects. Screenshots are written as PNG files next to the staged rows, with an element
 * crop for entities that carry a bounding box, and an action crop around the point a pointer step aimed at. Bounding
 * boxes are in CSS pixels and screenshots in device pixels; OdoBot's Firefox runs at a device pixel ratio of 1, so they
 * are treated as the same.</p>
 *
 * <p>Each uncharted step is linked to the charted entities it caused (see docs/capturing-trajectories-at-scale):
 * {@code seq_window} links an entity built from events that arrived while the step ran and its page settled, with the
 * share of its events inside that window as confidence; {@code spatial} links an entity whose bounding box holds the
 * point a pointer step aimed at.</p>
 */
public final class TrajectoryWriter {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryWriter.class);

    /**
     * Pixels of context around an element crop.
     */
    static final int CROP_PADDING = 8;

    /**
     * Side of the square action crop, centred on the point a pointer step aimed at.
     */
    static final int ACTION_CROP_SIZE = 200;

    /**
     * Network headers whose values are replaced, since they carry the browser's session.
     */
    static final Set<String> REDACTED_HEADERS = Set.of("cookie", "set-cookie", "authorization", "proxy-authorization");

    static final String REDACTED = "[redacted]";

    private static final Set<String> POINTER_ACTIONS = Set.of("left_click", "double_click", "triple_click");

    private TrajectoryWriter() {
    }

    /**
     * What the harness knows of a task run besides its timelines and steps.
     *
     * @param task           the task as received in the evaluate request.
     * @param agentConfig    the agent's settings, secrets removed, or null.
     * @param evaluation     the WebArena scorer's result: {@code result}, {@code score}, {@code error}, or null if the
     *                       task was not scored.
     * @param evalReport     the full {@code <evalId>-webarena-eval.json}, or null.
     */
    public record TaskRun(String experimentId, String evalId, JsonObject task, String executionMode, String agent,
                          String model, JsonObject agentConfig, boolean reasoningRequired, String odobotCommit,
                          String odoxVersion, long collectedAt, Long endedAt, String endReason, String harnessError,
                          String finalUrl, String agentAnswer, boolean judgedInfeasible, Integer webArenaTaskId,
                          JsonObject evaluation, JsonObject evalReport) {
    }

    /**
     * Build the trajectory, writing its screenshots into the staging folder, then write the staged rows. If building
     * fails, a fallback holding what could be read is staged instead, with the failure as {@code build_error}.
     *
     * @param processor the task's event processor, or null if the task never got one (setup failure).
     * @param ledger    the task's steps, or null.
     * @return the staged trajectory.
     */
    public static StagedTrajectory stage(TaskRun run, OnlineEventProcessor processor, StepLedger ledger, Path folder) throws IOException {
        Files.createDirectories(folder);
        StagedTrajectory staged;
        try {
            staged = build(run, processor, ledger, folder);
        } catch (RuntimeException | IOException e) {
            log.error("Could not build the trajectory of {}, staging what can be read: {}", run.evalId(), e.getMessage(), e);
            staged = fallback(run, processor, ledger, e);
        }
        staged.write(folder);
        return staged;
    }

    static StagedTrajectory build(TaskRun run, OnlineEventProcessor processor, StepLedger ledger, Path folder) throws IOException {
        String fileStem = fileStem(run.evalId());
        Builder builder = new Builder(run, fileStem, folder);
        if (processor != null) {
            List<TimelineEntity> charted = processor.timeline();
            List<TimelineEntity> uncharted = processor.unchartedTimeline();
            for (int i = 0; i < charted.size(); i++) {
                builder.entity("charted", i, charted.get(i), processor);
            }
            for (int i = 0; i < uncharted.size(); i++) {
                builder.entity("uncharted", i, uncharted.get(i), processor);
            }
            if (ledger != null) {
                List<StepLedger.Entry> entries = ledger.entries();
                for (int i = 0; i < entries.size(); i++) {
                    StepLedger.Entry next = i + 1 < entries.size() ? entries.get(i + 1) : null;
                    builder.step(entries.get(i), next, processor);
                }
            }
        }
        return builder.staged(null);
    }

    /**
     * What can be read of the run without building rows: the trajectory, each entity's own JSON, and each step's
     * thoughts. Screenshots already written are kept.
     */
    static StagedTrajectory fallback(TaskRun run, OnlineEventProcessor processor, StepLedger ledger, Throwable cause) {
        String fileStem = fileStem(run.evalId());
        Builder builder = new Builder(run, fileStem, null);
        if (processor != null) {
            addFallbackEntities(builder, "charted", safely(processor::timeline, List.of()));
            addFallbackEntities(builder, "uncharted", safely(processor::unchartedTimeline, List.of()));
        }
        if (ledger != null) {
            for (StepLedger.Entry entry : safely(ledger::entries, List.<StepLedger.Entry>of())) {
                JsonObject step = new JsonObject().put("step_number", entry.stepNumber)
                        .put("emitted", entry.emitted)
                        .put("lost", entry.lost);
                if (entry.trace != null) {
                    step.put("raw_response", entry.trace.rawResponse())
                            .put("reasoning", entry.trace.reasoning())
                            .put("low_level_instruction", entry.trace.lowLevelInstruction());
                }
                builder.steps.add(step);
            }
        }
        return builder.staged(cause.toString());
    }

    private static void addFallbackEntities(Builder builder, String timeline, List<TimelineEntity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            TimelineEntity entity = entities.get(i);
            builder.entities.add(new JsonObject()
                    .put("timeline", timeline)
                    .put("position", i)
                    .put("kind", entity.getClass().getSimpleName())
                    .put("details", safely(entity::toJson, new JsonObject())));
        }
    }

    /**
     * @return the evalId as it appears in file names, as {@link ca.ualberta.odobot.common.TaskArtifacts#fileStem}.
     */
    static String fileStem(String evalId) {
        return evalId == null ? "unknown" : evalId.replace('|', '-');
    }

    // ---------------------------------------------------------------- builder

    private static final class Builder {

        private final TaskRun run;
        private final String fileStem;
        private final Path folder;
        private final String trajectoryId = UUID.randomUUID().toString();

        private final JsonArray entities = new JsonArray();
        private final JsonObject doms = new JsonObject();
        private final JsonArray steps = new JsonArray();
        private final JsonArray links = new JsonArray();
        private final List<String> files = new ArrayList<>();

        /**
         * The rows of the charted entities, by entity, to link steps to.
         */
        private final Map<TimelineEntity, JsonObject> chartedRows = new IdentityHashMap<>();

        /**
         * The key of each uncharted timeline entity, to link steps to their observations.
         */
        private final Map<TimelineEntity, String> unchartedKeys = new IdentityHashMap<>();

        /**
         * The file written for each screenshot, so a screenshot on both timelines is written once.
         */
        private final Map<Screenshot, String> screenshotFiles = new IdentityHashMap<>();

        Builder(TaskRun run, String fileStem, Path folder) {
            this.run = run;
            this.fileStem = fileStem;
            this.folder = folder;
        }

        void entity(String timeline, int position, TimelineEntity entity, OnlineEventProcessor processor) throws IOException {
            String key = timeline + ":" + position;
            JsonObject row = new JsonObject()
                    .put("timeline", timeline)
                    .put("position", position)
                    .put("kind", entity.getClass().getSimpleName())
                    .put("symbol", safely(entity::symbol, null));

            List<Integer> sources = processor.sourceEvents(entity);
            if (!sources.isEmpty()) {
                row.put("first_seq", sources.get(0)).put("last_seq", sources.get(sources.size() - 1));
            }
            JsonObject facts = processor.eventFactsOf(entity);
            row.put("event_name", facts.getString("name"));

            JsonObject details = new JsonObject();
            Screenshot screenshot = null;
            BoundingBox bbox = null;
            switch (entity) {
                case Observation observation -> {
                    screenshot = observation.getScreenshot();
                    row.put("timestamp", observation.timestamp())
                            .put("clock", observation.getTrigger() == Observation.Trigger.START_TRANSMISSION ? "browser" : "server")
                            .put("location", observation.getUserLocation())
                            .put("event_name", "OBSERVATION");
                    details.put("trigger", observation.getTrigger().name())
                            .put("triggerDetails", observation.getTriggerDetails())
                            .put("stepNumber", observation.getStepNumber())
                            .put("instructionId", observation.getInstructionId());
                }
                case ClickEvent click -> {
                    screenshot = click.getScreenshot();
                    bbox = click.getBoundingBox();
                    artifact(row, click);
                    Element target = safely(click::getTriggerElement, null);
                    details.put("elementHtml", target == null ? null : target.outerHtml())
                            .put("text", target == null ? null : target.text());
                }
                case CheckboxEvent checkbox -> {
                    screenshot = checkbox.getScreenshot();
                    bbox = checkbox.getBoundingBox();
                    artifact(row, checkbox);
                    details.put("outerHTML", checkbox.getOuterHTML() != null ? checkbox.getOuterHTML()
                                    : checkbox.getInputElement() == null ? null : checkbox.getInputElement().outerHtml())
                            .put("checked", facts.getValue("checked"));
                }
                case DataEntry entry -> {
                    InputChange last = entry.lastChange();
                    InputChange first = entry.isEmpty() ? null : entry.get(0);
                    if (last != null) {
                        screenshot = last.getScreenshot();
                        bbox = last.getBoundingBox();
                        artifact(row, last);
                        row.put("timestamp", first.getTimestamp() == null ? null : first.getTimestamp().toInstant().toEpochMilli());
                        details.put("enteredData", last.getValue())
                                .put("inputElement", last.getInputElement() == null ? null : last.getInputElement().outerHtml())
                                .put("placeholderText", last.getPlaceholderText())
                                .put("inputType", facts.getValue("inputType"))
                                .put("changeKind", last.getClass().getSimpleName())
                                .put("firstTimestamp", row.getLong("timestamp"))
                                .put("lastTimestamp", last.getTimestamp() == null ? null : last.getTimestamp().toInstant().toEpochMilli());
                        if (last instanceof RadioButtonEvent radio) {
                            details.put("radioGroup", radio.getRadioGroup())
                                    .put("options", new JsonArray(radio.getOptions().stream().map(RadioButtonEvent.RadioButton::toJson).toList()));
                        }
                        if (last instanceof TinymceEvent tinymce) {
                            details.put("editorId", tinymce.getEditorId())
                                    .put("editorContent", tinymce.getValue())
                                    .put("inputType", tinymce.getInputType());
                        }
                    }
                }
                case SelectEvent select -> {
                    screenshot = select.getScreenshot();
                    bbox = select.getBoundingBox();
                    artifact(row, select);
                    SelectEvent.Option selected = select.getSelectedOption();
                    details.put("outerHTML", select.selectElement() == null ? null : select.selectElement().outerHtml())
                            .put("options", new JsonArray(select.options().stream().map(SelectEvent.Option::toJson).toList()))
                            .put("selected", selected == null ? null : selected.toJson());
                }
                case Effect effect -> {
                    details.mergeIn(effectDetails(effect));
                    if (!effect.isEmpty()) {
                        DomEffect firstEffect = effect.get(0);
                        row.put("timestamp", firstEffect.getTimestamp() == null ? null : firstEffect.getTimestamp().toInstant().toEpochMilli())
                                .put("clock", "browser")
                                .put("location", firstEffect.getBaseURI() == null ? null : firstEffect.getBaseURI().toString());
                    }
                }
                case NetworkEvent network -> {
                    row.put("timestamp", network.getTimestamp() == null ? null : network.getTimestamp().toInstant().toEpochMilli())
                            .put("clock", "browser")
                            .put("location", network.getUrl())
                            .put("event_name", "NETWORK_EVENT");
                    details.put("method", network.getMethod())
                            .put("url", network.getUrl())
                            .put("path", safely(network::getPath, null))
                            .put("query", safely(network::getQuery, null))
                            .put("documentUrl", network.getDocumentUrl())
                            .put("type", network.getType())
                            .put("requestHeaders", redactHeaders(network.getRequestHeaders()))
                            .put("responseHeaders", redactHeaders(network.getResponseHeaders()))
                            .put("requestBody", network.getRequestObject() != null ? network.getRequestObject() : network.getRequestArray())
                            .put("responseBody", network.getResponseObject() != null ? network.getResponseObject() : network.getResponseArray())
                            .put("millisecondTimestamp", network.getMillisecondTimestamp());
                }
                case ApplicationLocationChange change -> {
                    row.put("timestamp", change.getTimestamp() == null ? null : change.getTimestamp().toInstant().toEpochMilli())
                            .put("clock", "browser")
                            .put("location", change.getTo() == null ? null : change.getTo().toString());
                    details.put("from", change.getFrom() == null ? null : change.getFrom().toString())
                            .put("to", change.getTo() == null ? null : change.getTo().toString());
                }
                case TaskAnswer answer -> {
                    row.put("timestamp", answer.timestamp()).put("clock", "server");
                    details.put("answer", answer.getAnswer());
                }
                case NoOpEvent noOp -> row.put("timestamp", noOp.timestamp()).put("clock", "server");
                default -> {
                    row.put("timestamp", safely(entity::timestamp, null));
                    details.mergeIn(safely(entity::toJson, new JsonObject()));
                }
            }

            if (bbox != null) {
                row.put("bbox", bbox.toJson());
            }
            if (screenshot != null && screenshot.getImage() != null) {
                row.put("screenshot_path", screenshot(screenshot, timeline + "-" + position + ".png"));
                if (bbox != null) {
                    JsonObject cropBox = cropBox(screenshot.getImage(), bbox, CROP_PADDING);
                    if (cropBox != null) {
                        String cropFile = timeline + "-" + position + "-crop.png";
                        writeCrop(screenshot.getImage(), cropBox, cropFile);
                        row.put("crop_path", committed(cropFile)).put("crop_box", cropBox);
                    }
                }
            }

            String html = processor.domSnapshotOf(entity);
            if (html != null) {
                String sha = sha256(html);
                doms.put(sha, html);
                row.put("dom_sha256", sha);
            }

            row.put("details", details);
            entities.add(row);
            if (timeline.equals("charted") && !(entity instanceof Observation)) {
                chartedRows.put(entity, row);
            }
            if (timeline.equals("uncharted")) {
                unchartedKeys.put(entity, key);
            }
        }

        void step(StepLedger.Entry entry, StepLedger.Entry next, OnlineEventProcessor processor) throws IOException {
            AgentTrace trace = entry.trace;
            JsonObject row = new JsonObject()
                    .put("step_number", entry.stepNumber)
                    .put("emitted", entry.emitted)
                    .put("not_executed", entry.notExecuted)
                    .put("instruction_id", entry.instructionId)
                    .put("sent_at", entry.sentAt)
                    .put("done_at", entry.doneAt)
                    .put("done_status", entry.doneStatus)
                    .put("done_reason", entry.doneReason)
                    .put("observed_at", entry.observedAt)
                    .put("seq_at_send", entry.eventAtSend)
                    .put("seq_at_observation", entry.eventAtObservation)
                    .put("lost", entry.lost)
                    .put("result_observation_key", entry.resultObservation == null ? null : unchartedKeys.get(entry.resultObservation));
            if (trace != null) {
                row.put("request_id", trace.requestId())
                        .put("input_observation_key", trace.inputObservation() == null ? null : unchartedKeys.get(trace.inputObservation()))
                        .put("raw_response", trace.rawResponse())
                        .put("reasoning", trace.reasoning())
                        .put("committed_response", trace.committedResponse())
                        .put("low_level_instruction", trace.lowLevelInstruction())
                        .put("action", trace.action())
                        .put("pyautogui", new JsonArray(trace.pyAutoGui()))
                        .put("unsupported", new JsonArray(trace.unsupported()))
                        .put("terminal", trace.terminal())
                        .put("answer", trace.answer())
                        .put("prompt_messages", trace.promptMessages())
                        .put("prompt_tokens", trace.promptTokens())
                        .put("completion_tokens", trace.completionTokens())
                        .put("provider", trace.provider())
                        .put("reasoning_tokens", trace.reasoningTokens())
                        .put("reasoning_source", trace.reasoningSource())
                        .put("reasoning_formats", new JsonArray(trace.reasoningFormats()))
                        .put("reasoning_complete", trace.reasoningComplete())
                        .put("reasoning_problem", trace.reasoningProblem())
                        .put("original_size", new JsonArray().add(trace.originalWidth()).add(trace.originalHeight()))
                        .put("processed_size", new JsonArray().add(trace.processedWidth()).add(trace.processedHeight()))
                        .put("answered_at", trace.answeredAt());
                actionCrop(row, trace);
            }
            steps.add(row);
            linkSeqWindow(entry, next, processor);
            linkSpatial(entry, next, processor);
        }

        /**
         * The window of a step: the events after it was sent, up to its observation, or, if it was never observed, up
         * to the next step's send.
         *
         * @return {from (exclusive), to (inclusive)}, or null if the step was never sent.
         */
        private int[] window(StepLedger.Entry entry, StepLedger.Entry next) {
            if (entry.eventAtSend == null) {
                return null;
            }
            int to = entry.eventAtObservation != null ? entry.eventAtObservation
                    : next != null && next.eventAtSend != null ? next.eventAtSend : Integer.MAX_VALUE;
            return new int[]{entry.eventAtSend, to};
        }

        private void linkSeqWindow(StepLedger.Entry entry, StepLedger.Entry next, OnlineEventProcessor processor) {
            int[] window = window(entry, next);
            if (window == null) {
                return;
            }
            chartedRows.forEach((entity, row) -> {
                List<Integer> sources = processor.sourceEvents(entity);
                if (sources.isEmpty()) {
                    return;
                }
                long inside = sources.stream().filter(index -> index > window[0] && index <= window[1]).count();
                if (inside > 0) {
                    links.add(new JsonObject()
                            .put("step_number", entry.stepNumber)
                            .put("entity_key", row.getString("timeline") + ":" + row.getInteger("position"))
                            .put("method", "seq_window")
                            .put("confidence", (double) inside / sources.size())
                            .put("details", new JsonObject()
                                    .put("window", new JsonArray().add(window[0]).add(window[1] == Integer.MAX_VALUE ? null : window[1]))
                                    .put("eventsInside", inside)
                                    .put("events", sources.size())));
                }
            });
        }

        private void linkSpatial(StepLedger.Entry entry, StepLedger.Entry next, OnlineEventProcessor processor) {
            JsonObject action = entry.trace == null ? null : entry.trace.action();
            int[] window = window(entry, next);
            if (action == null || window == null || !POINTER_ACTIONS.contains(action.getString("action"))
                    || action.getValue("x") == null || action.getValue("y") == null) {
                return;
            }
            double x = action.getDouble("x");
            double y = action.getDouble("y");
            chartedRows.forEach((entity, row) -> {
                JsonObject bbox = row.getJsonObject("bbox");
                if (bbox == null) {
                    return;
                }
                List<Integer> sources = processor.sourceEvents(entity);
                if (sources.stream().noneMatch(index -> index > window[0] && index <= window[1])) {
                    return;
                }
                double left = bbox.getDouble("left"), right = bbox.getDouble("right");
                double top = bbox.getDouble("top"), bottom = bbox.getDouble("bottom");
                if (x >= left && x <= right && y >= top && y <= bottom) {
                    double dx = x - (left + right) / 2, dy = y - (top + bottom) / 2;
                    links.add(new JsonObject()
                            .put("step_number", entry.stepNumber)
                            .put("entity_key", row.getString("timeline") + ":" + row.getInteger("position"))
                            .put("method", "spatial")
                            .put("confidence", 1.0)
                            .put("details", new JsonObject()
                                    .put("point", new JsonArray().add(x).add(y))
                                    .put("bbox", bbox)
                                    .put("distanceToCenter", Math.sqrt(dx * dx + dy * dy))));
                }
            });
        }

        /**
         * A square crop of the screenshot the model reasoned over, centred on the point a pointer step aimed at.
         */
        private void actionCrop(JsonObject row, AgentTrace trace) throws IOException {
            JsonObject action = trace.action();
            if (action == null || action.getValue("x") == null || action.getValue("y") == null
                    || !(trace.inputObservation() instanceof Observation observation)
                    || observation.getScreenshot() == null || observation.getScreenshot().getImage() == null) {
                return;
            }
            BufferedImage image = observation.getScreenshot().getImage();
            int half = ACTION_CROP_SIZE / 2;
            JsonObject box = clamp(image, (int) Math.round(action.getDouble("x")) - half, (int) Math.round(action.getDouble("y")) - half,
                    ACTION_CROP_SIZE, ACTION_CROP_SIZE);
            if (box == null) {
                return;
            }
            String file = "step-" + trace.stepNumber() + "-action.png";
            writeCrop(image, box, file);
            row.put("action_crop_path", committed(file));
        }

        private String screenshot(Screenshot screenshot, String fileName) throws IOException {
            String existing = screenshotFiles.get(screenshot);
            if (existing != null) {
                return committed(existing);
            }
            writePng(screenshot.getImage(), fileName);
            screenshotFiles.put(screenshot, fileName);
            return committed(fileName);
        }

        private void writeCrop(BufferedImage image, JsonObject box, String fileName) throws IOException {
            writePng(image.getSubimage(box.getInteger("x"), box.getInteger("y"), box.getInteger("width"), box.getInteger("height")), fileName);
        }

        private void writePng(BufferedImage image, String fileName) throws IOException {
            if (folder == null) {
                return;
            }
            if (!javax.imageio.ImageIO.write(image, "png", folder.resolve(fileName).toFile())) {
                throw new IOException("No PNG writer for " + fileName);
            }
            files.add(fileName);
        }

        private String committed(String fileName) {
            return TrajectoryStore.committedPath(run.experimentId(), fileStem, fileName);
        }

        StagedTrajectory staged(String buildError) {
            JsonObject report = run.evalReport();
            JsonObject evaluation = run.evaluation();
            JsonObject trajectory = new JsonObject()
                    .put("id", trajectoryId)
                    .put("eval_id", run.evalId())
                    .put("experiment_id", run.experimentId())
                    .put("task_id", run.task() == null ? null : run.task().getString("id"))
                    .put("webarena_task_id", run.webArenaTaskId())
                    .put("task_description", run.task() == null ? "" : run.task().getString("task", ""))
                    .put("task_definition", scrubTask(run.task()))
                    .put("execution_mode", run.executionMode())
                    .put("agent", run.agent())
                    .put("model", run.model())
                    .put("agent_config", run.agentConfig())
                    .put("reasoning_required", run.reasoningRequired())
                    .put("odobot_commit", run.odobotCommit())
                    .put("odox_version", run.odoxVersion())
                    .put("collected_at", run.collectedAt())
                    .put("ended_at", run.endedAt())
                    .put("end_reason", run.endReason())
                    .put("harness_error", run.harnessError())
                    .put("final_url", run.finalUrl())
                    .put("eval_result", evaluation == null ? null : evaluation.getString("result"))
                    .put("score", evaluation == null ? null : evaluation.getValue("score"))
                    .put("eval_error", evaluation == null ? null : evaluation.getString("error"))
                    .put("agent_answer", report != null && report.containsKey("agentAnswer") ? report.getString("agentAnswer") : run.agentAnswer())
                    .put("judged_infeasible", report != null && report.containsKey("judgedInfeasible") ? report.getValue("judgedInfeasible") : run.judgedInfeasible())
                    .put("eval_types", report == null ? null : report.getValue("evalTypes"))
                    .put("judge_model", report == null ? null : report.getString("judgeModel"))
                    .put("eval_report", report)
                    .put("build_error", buildError);
            return new StagedTrajectory(run.experimentId(), run.evalId(), fileStem, trajectory, entities, doms, steps, links,
                    List.copyOf(files));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void artifact(JsonObject row, AbstractArtifact artifact) {
        row.put("timestamp", artifact.getTimestamp() == null ? null : artifact.getTimestamp().toInstant().toEpochMilli())
                .put("clock", "browser")
                .put("location", artifact.getBaseURI() == null ? null : artifact.getBaseURI().toString())
                .put("xpath", artifact.getXpath())
                .put("tag", artifact.getTag())
                .put("html_id", artifact.getHtmlId());
    }

    /**
     * An Effect built during a live run holds no parsed elements, so what it made visible or invisible is described by
     * each DOM effect's primary node: its xpath, tag, id and text.
     */
    static JsonObject effectDetails(Effect effect) {
        Map<String, Integer> actions = new TreeMap<>();
        Map<String, JsonObject> visible = new LinkedHashMap<>();
        Map<String, JsonObject> invisible = new LinkedHashMap<>();
        Set<String> baseUris = new LinkedHashSet<>();
        for (DomEffect domEffect : effect) {
            String action = domEffect.getAction() == null ? "UNKNOWN" : domEffect.getAction().name();
            actions.merge(action, 1, Integer::sum);
            if (domEffect.getBaseURI() != null) {
                baseUris.add(domEffect.getBaseURI().toString());
            }
            JsonObject node = new JsonObject()
                    .put("xpath", domEffect.getXpath())
                    .put("tag", domEffect.getTag())
                    .put("htmlId", domEffect.getHtmlId())
                    .put("text", domEffect.getText());
            String key = String.valueOf(domEffect.getXpath());
            switch (action) {
                case "ADD", "SHOW" -> visible.put(key, node);
                case "REMOVE", "HIDE" -> invisible.put(key, node);
                default -> {
                }
            }
        }
        Map<String, JsonObject> netVisible = new LinkedHashMap<>(visible);
        netVisible.keySet().removeAll(invisible.keySet());
        DomEffect first = effect.isEmpty() ? null : effect.get(0);
        DomEffect last = effect.isEmpty() ? null : effect.get(effect.size() - 1);
        return new JsonObject()
                .put("baseURIs", new JsonArray(new ArrayList<>(baseUris)))
                .put("domEffectCount", effect.size())
                .put("actions", new JsonObject(new HashMap<>(actions)))
                .put("madeVisible", new JsonArray(new ArrayList<>(visible.values())))
                .put("madeInvisible", new JsonArray(new ArrayList<>(invisible.values())))
                .put("netVisible", new JsonArray(new ArrayList<>(netVisible.values())))
                .put("firstTimestamp", first == null || first.getTimestamp() == null ? null : first.getTimestamp().toInstant().toEpochMilli())
                .put("lastTimestamp", last == null || last.getTimestamp() == null ? null : last.getTimestamp().toInstant().toEpochMilli());
    }

    /**
     * @return the headers with the values of {@link #REDACTED_HEADERS} replaced, or null.
     */
    static JsonObject redactHeaders(JsonObject headers) {
        if (headers == null) {
            return null;
        }
        JsonObject redacted = headers.copy();
        for (String name : headers.fieldNames()) {
            if (REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                redacted.put(name, REDACTED);
            }
        }
        return redacted;
    }

    /**
     * @return the task with the browser sessions removed: every {@code storageState} under its {@code environment}.
     */
    static JsonObject scrubTask(JsonObject task) {
        if (task == null) {
            return new JsonObject();
        }
        JsonObject scrubbed = task.copy();
        Object environment = scrubbed.getValue("environment");
        if (environment instanceof JsonObject env) {
            removeKey(env, "storageState");
        }
        return scrubbed;
    }

    private static void removeKey(Object node, String key) {
        if (node instanceof JsonObject object) {
            object.remove(key);
            object.forEach(field -> removeKey(field.getValue(), key));
        } else if (node instanceof JsonArray array) {
            array.forEach(item -> removeKey(item, key));
        }
    }

    /**
     * @return the bounding box grown by {@code padding} and clamped to the image, as {x, y, width, height}, or null if
     * it lies outside the image or is empty.
     */
    static JsonObject cropBox(BufferedImage image, BoundingBox bbox, int padding) {
        return clamp(image, bbox.getX() - padding, bbox.getY() - padding, bbox.getWidth() + 2 * padding, bbox.getHeight() + 2 * padding);
    }

    static JsonObject clamp(BufferedImage image, int x, int y, int width, int height) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(image.getWidth(), x + width);
        int bottom = Math.min(image.getHeight(), y + height);
        if (right - left < 1 || bottom - top < 1) {
            return null;
        }
        return new JsonObject().put("x", left).put("y", top).put("width", right - left).put("height", bottom - top);
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T safely(Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
