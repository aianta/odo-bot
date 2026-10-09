package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.guidance.AbstractAgent;
import ca.ualberta.odobot.guidance.UnchartedAgent;
import ca.ualberta.odobot.guidance.instructions.GiveUp;
import ca.ualberta.odobot.guidance.instructions.TaskComplete;
import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedInstruction;
import ca.ualberta.odobot.guidance.instructions.uncharted.UnchartedStep;
import ca.ualberta.odobot.guidance.instructions.uncharted.Wait;
import ca.ualberta.odobot.guidance.uncharted.QwenImages.ProcessedImage;
import ca.ualberta.odobot.guidance.uncharted.QwenModelClient.QwenCompletion;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screenshot-driven agent backed by Qwen3.8. Port of OSWorld's {@code mm_agents/qwen3_8.py}.
 *
 * <p>Every timeline entity delivered to {@link #observationHandler} is treated as a step observation carrying a
 * screenshot. For each one the agent asks the model for the next move and emits it as a single
 * {@link UnchartedStep}, followed by {@link TaskComplete} or {@link GiveUp} when the model ends the task. It
 * expects no execution result; the harness delivers the next observation once the step's effects have been
 * captured. The step limit is also the harness's job.</p>
 *
 * <p>Entities that arrive while the model is answering are ignored: they reflect the page before the pending
 * step was executed.</p>
 */
public class Qwen38Agent extends UnchartedAgent {

    private static final Logger log = LoggerFactory.getLogger(Qwen38Agent.class);

    private QwenAgentConfig config;
    private QwenModelClient modelClient;

    // Conversation state, as in the Python agent.
    private final List<String> thoughts = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private final List<String> responses = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private int foldedPrefixK = 0;

    private String lastRequestId;
    private String lastHiddenStatesPath;

    /**
     * True while a screenshot is being processed or the model is answering.
     */
    private boolean inFlight = false;

    public static class Builder extends AbstractAgent.Builder<Qwen38Agent, Builder> {

        private QwenAgentConfig config;
        private QwenModelClient modelClient;
        private LlmCallScope llmScope;

        public Builder config(QwenAgentConfig config) {
            this.config = config;
            return this;
        }

        /**
         * The task the default model client makes calls for, so they are counted toward it. Optional: without it calls
         * are reported to {@link ca.ualberta.odobot.guidance.TokenUsageRecord#active}.
         */
        public Builder llmScope(LlmCallScope llmScope) {
            this.llmScope = llmScope;
            return this;
        }

        /**
         * Optional. Defaults to an {@link OpenAIQwenModelClient} for the config.
         */
        public Builder modelClient(QwenModelClient modelClient) {
            this.modelClient = modelClient;
            return this;
        }

        /**
         * The Qwen agent does not use the navigation model, so the graph, Neo4J and SQLite services are optional.
         */
        @Override
        protected boolean validate() {
            return config != null;
        }

        @Override
        public Qwen38Agent build() {
            Qwen38Agent agent = super.build();
            if (agent.task == null || agent.task.getString("task") == null || agent.artifactDir == null || agent.evalId == null) {
                throw new IllegalStateException("Cannot build Qwen38Agent, a task with a 'task' description, an artifactDir and an evalId are required.");
            }
            return agent;
        }

        @Override
        protected Qwen38Agent create() {
            Qwen38Agent agent = new Qwen38Agent();
            agent.config = config;
            agent.modelClient = modelClient != null ? modelClient : new OpenAIQwenModelClient(config, llmScope);
            return agent;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    @Override
    public void observationHandler(TimelineEntity timelineEntity) {
        if (stopped && timelineEntity instanceof Observation) {
            log.info("Observation received, re-activating the Qwen3.8 agent.");
            stopped = false;
        }
        if (stopped) {
            return;
        }
        if (inFlight) {
            log.info("A step is in progress, ignoring {}.", timelineEntity.getClass().getSimpleName());
            return;
        }

        BufferedImage image = screenshotOf(timelineEntity);
        if (image == null) {
            return;
        }

        Context context = Vertx.currentContext();
        if (context == null) {
            log.error("Qwen38Agent must be called on a Vert.x context, ignoring {}.", timelineEntity.getClass().getSimpleName());
            return;
        }
        step(context, image);
    }

    @Override
    public void stop() {
        stopped = true;
        modelClient.close();
    }

    /**
     * Port of {@code Qwen38Agent.predict}. The resize and encoding run on a worker thread and the model call is
     * asynchronous; everything that touches agent state, and every emit, runs on {@code context}.
     */
    private void step(Context context, BufferedImage image) {
        inFlight = true;
        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();

        context.executeBlocking(() -> QwenImages.process(image))
                .compose(processed -> {
                    JsonArray messages = buildMessages(processed);
                    int stepIndex = screenshots.size() - 1;
                    writeArtifact(context, "qwen38-messages-step-%d.json".formatted(stepIndex),
                            QwenHistory.sanitizeForDump(messages).encodePrettily(), false);
                    return modelClient.complete(context, messages)
                            .map(completion -> new ModelAnswer(completion, processed, stepIndex + 1));
                })
                .onComplete(result -> {
                    inFlight = false;
                    if (result.failed()) {
                        onStepFailed(result.cause());
                        return;
                    }
                    onModelAnswer(context, image, result.result(), originalWidth, originalHeight);
                });
    }

    private record ModelAnswer(QwenCompletion completion, ProcessedImage processed, int stepNumber) {
    }

    /**
     * Records the screenshot, updates folding and assembles the conversation for this step.
     */
    private JsonArray buildMessages(ProcessedImage processed) {
        screenshots.add(processed.base64Png());
        int totalSteps = screenshots.size();
        foldedPrefixK = QwenHistory.updateFoldingState(totalSteps, foldedPrefixK, config.imageMax(), config.foldSize());

        int startStep = Math.max(1, totalSteps - config.historyN());
        String previousActions = QwenHistory.previousActionsText(actions, startStep);

        JsonObject toolsDef = QwenPrompts.toolsDef(processed.width(), processed.height(), config.coordinateType());
        String systemPrompt = QwenPrompts.systemPrompt(toolsDef, config.collapseText());
        String instructionPrompt = QwenPrompts.instructionPrompt(task.getString("task"), previousActions);

        return QwenHistory.buildMessages(systemPrompt, instructionPrompt, screenshots, responses,
                startStep, totalSteps, foldedPrefixK, config.collapseText());
    }

    private void onModelAnswer(Context context, BufferedImage image, ModelAnswer answer, int originalWidth, int originalHeight) {
        QwenCompletion completion = answer.completion();
        String response = completion.text() == null ? "" : completion.text();
        lastRequestId = completion.requestId();
        lastHiddenStatesPath = completion.hiddenStatesPath();
        log.info("Qwen3.8 Output: {}", response);

        // History carries the committed answer only; Qwen degrades when fed its own stale reasoning.
        thoughts.add(response);
        responses.add(QwenResponseParser.stripReasoning(response));

        ParsedStep parsed = QwenResponseParser.parseResponse(response, config.coordinateType(),
                originalWidth, originalHeight, answer.processed().width(), answer.processed().height());
        log.info("Low level instruction: {}", parsed.lowLevelInstruction());
        log.info("Pyautogui code: {}", parsed.pyAutoGuiCodes());

        String lowLevelInstruction = parsed.lowLevelInstruction();
        UnchartedInstruction stepAction = parsed.action();
        if (!parsed.unsupported().isEmpty()) {
            log.warn("Not executed at step {}: {}", answer.stepNumber(), parsed.unsupported());
            // The previous actions prompt tells the model what was not done, and why.
            lowLevelInstruction += " [not executed: " + String.join("; ", parsed.unsupported()) + "]";
            if (stepAction == null && parsed.terminal() == ParsedStep.Terminal.NONE) {
                // Nothing to execute, but the task goes on: wait, so that a new observation comes.
                stepAction = new Wait(null);
            }
        }
        actions.add(lowLevelInstruction);

        UnchartedStep step = stepAction == null
                ? null
                : new UnchartedStep(answer.stepNumber(), lowLevelInstruction, stepAction);
        writeTrajectory(context, answer, response, parsed, step, originalWidth, originalHeight);

        if (stopped) {
            log.info("Agent was stopped while the model was answering, dropping step {}.", answer.stepNumber());
            return;
        }

        if (step != null) {
            emit(step);
        }

        switch (parsed.terminal()) {
            case DONE -> {
                emit(new TaskComplete(parsed.answer()));
                stopped = true;
            }
            case FAIL -> {
                emit(new GiveUp("Qwen3.8 ended the task as failed: " + parsed.lowLevelInstruction()));
                stopped = true;
            }
            case NONE -> {
                if (step == null) {
                    // An empty response executes nothing, so no new observation will come. OSWorld predicts
                    // again on the unchanged screen; do the same.
                    log.warn("Empty response at step {}, predicting again on the same screenshot.", answer.stepNumber());
                    step(context, image);
                }
            }
        }
    }

    private void onStepFailed(Throwable err) {
        if (stopped) {
            log.info("Qwen3.8 step failed after the agent was stopped: {}", err.getMessage());
            return;
        }
        log.error("Qwen3.8 step failed: {}", err.getMessage(), err);
        emit(new GiveUp("Qwen3.8 step failed: " + err.getMessage()));
        stopped = true;
    }

    private static BufferedImage screenshotOf(TimelineEntity entity) {
        Screenshot screenshot;
        try {
            screenshot = entity.getScreenshot();
        } catch (RuntimeException e) {
            log.warn("Could not get a screenshot from {}: {}", entity.getClass().getSimpleName(), e.getMessage());
            return null;
        }
        if (screenshot == null || screenshot.getImage() == null) {
            log.warn("{} carries no screenshot, ignoring it.", entity.getClass().getSimpleName());
            return null;
        }
        return screenshot.getImage();
    }

    private void writeTrajectory(Context context, ModelAnswer answer, String response, ParsedStep parsed,
                                 UnchartedStep step, int originalWidth, int originalHeight) {
        JsonObject line = new JsonObject()
                .put("step_num", answer.stepNumber())
                .put("request_id", answer.completion().requestId())
                .put("hidden_states_path", answer.completion().hiddenStatesPath())
                .put("response", response)
                .put("low_level_instruction", parsed.lowLevelInstruction())
                .put("terminal", parsed.terminal().name())
                .put("answer", parsed.answer())
                .put("pyautogui", new JsonArray(parsed.pyAutoGuiCodes()))
                .put("unsupported", new JsonArray(parsed.unsupported()))
                .put("instruction", step == null ? null : step.toJson())
                .put("original_size", new JsonArray().add(originalWidth).add(originalHeight))
                .put("processed_size", new JsonArray().add(answer.processed().width()).add(answer.processed().height()));
        writeArtifact(context, "qwen38-trajectory.jsonl", line.encode() + "\n", true);
    }

    /**
     * Writes on the context's ordered worker queue, so writes land in the order they were requested.
     */
    private void writeArtifact(Context context, String suffix, String content, boolean append) {
        Path path = Path.of(artifactPath(suffix));
        context.executeBlocking(() -> {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            return null;
        }).onFailure(err -> log.warn("Could not write {}: {}", path, err.getMessage()));
    }

    public String lastRequestId() {
        return lastRequestId;
    }

    public String lastHiddenStatesPath() {
        return lastHiddenStatesPath;
    }

    /**
     * The full responses, reasoning included.
     */
    public List<String> thoughts() {
        return Collections.unmodifiableList(thoughts);
    }

    /**
     * The low-level instruction of every step so far.
     */
    public List<String> actions() {
        return Collections.unmodifiableList(actions);
    }

    List<String> responses() {
        return Collections.unmodifiableList(responses);
    }

    boolean isInFlight() {
        return inFlight;
    }
}
