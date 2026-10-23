package ca.ualberta.odobot.task;

import ca.ualberta.odobot.common.LlmCallScope;
import ca.ualberta.odobot.common.TaskArtifacts;
import ca.ualberta.odobot.explorer.Agent;
import ca.ualberta.odobot.explorer.EvaluationTaskRequestFields;
import ca.ualberta.odobot.guidance.*;
import ca.ualberta.odobot.guidance.execution.ExecutionRequest;
import ca.ualberta.odobot.guidance.uncharted.Qwen38Agent;
import ca.ualberta.odobot.sqlite.SqliteService;
import ca.ualberta.odobot.task.webarena.WebArenaEvaluationSettings;
import ca.ualberta.odobot.task.webarena.WebArenaTaskConfig;
import ca.ualberta.odobot.task.webarena.WebArenaTaskEvaluator;
import ca.ualberta.odobot.telemetry.TelemetryService;
import ca.ualberta.odobot.telemetry.model.TaskInstanceResults;
import ca.ualberta.odobot.trajectory.BuildInfo;
import ca.ualberta.odobot.trajectory.TrajectoryStore;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static ca.ualberta.odobot.logpreprocessor.Constants.SQLITE_SERVICE_ADDRESS;
import static ca.ualberta.odobot.logpreprocessor.Constants.TELEMETRY_SERVICE_ADDRESS;

/**
 * Runs one task from start to finish, on a context of its own. The orchestrator deploys one per task.
 *
 * <ol>
 *     <li>Open a listener on a free port, for the task's OdoX.</li>
 *     <li>Start Firefox with OdoX pointed at that port (worker thread).</li>
 *     <li>Wait for OdoX's control and event sockets.</li>
 *     <li>Run the task: the agent and its harness ({@link RequestManager}) run on this verticle's event loop.</li>
 *     <li>Save the artifacts, then drop OdoX's events from then on, so that nothing the evaluators do in the browser
 *     reaches the task's event log.</li>
 *     <li>Record the final URL, score the task while the browser is still open (worker thread), and report its
 *     telemetry. WebArena tasks are scored by the ported WebArena evaluators, Canvas tasks by the evaluation script.</li>
 *     <li>Quit the browser (worker thread), close the sockets, and report the {@link TaskOutcome}.</li>
 * </ol>
 *
 * <p>Blocking work runs on the deployment's worker pool. Ordered blocking jobs are ordered per context, so each task's
 * jobs run in order without queuing behind other tasks'.</p>
 */
public class TaskVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TaskVerticle.class);

    /**
     * How long setup waits for OdoX's control and event sockets to connect, unless the request sets {@code odoXConnectTimeout}.
     */
    static final long ODOX_CONNECT_TIMEOUT_MS = 30000L;

    private final Agent agent;
    private final JsonObject request;
    private final JsonObject task;
    private final TaskEnvironment environment;
    private final JsonObject explorerConfig;
    private final Platform platform;
    private final Promise<TaskOutcome> outcome;

    private final String evalId;
    private final String experimentId;
    private final String experimentFolderPath;
    private final String experimentResultsFolderPath;

    /**
     * Where the task's artifacts go, or null if the task has no evalId.
     */
    private final TaskArtifacts artifacts;

    /**
     * OdoX's extension instance id, which is also its clientId on the sockets.
     */
    private final UUID clientId = UUID.randomUUID();

    /**
     * Wall clock time of this task, from before the browser is set up until after scoring.
     */
    private final TaskTiming timing = new TaskTiming();

    private TaskSockets sockets;
    private TaskBrowser browser;
    private HttpServer server;
    private OdoClient client;

    /**
     * Completes when the task's execution ends, once the task has been handed to the RequestManager. Null until then.
     */
    private Promise<Void> evaluation;

    /**
     * True once the task has started winding down, so it is wound down only once.
     */
    private boolean finishing = false;

    /**
     * The task's WebArena config, or null if it isn't a WebArena task scored by the ported evaluators.
     */
    private WebArenaTaskConfig webArenaConfig;

    private WebArenaEvaluationSettings webArenaSettings;

    /**
     * The WebArena scorer's result, or null until the task is scored.
     */
    private WebArenaTaskEvaluator.Result webArenaResult;

    /**
     * The URL of the agent's tab when the task ended, or null if it could not be read.
     */
    private String finalUrl;

    /**
     * Whether the task's trajectory is saved, see {@link TaskSettings#saveTrajectories}.
     */
    private final boolean saveTrajectory;

    /**
     * Epoch ms when the task started, the trajectory's collection time.
     */
    private long collectedAt;

    /**
     * How many times staging the trajectory is tried before the task gives up on it, and the wait before each retry.
     */
    static long[] stagingRetryDelaysMs = {5_000, 15_000, 30_000, 60_000, 90_000, 100_000};

    /**
     * @param agent the agent the experiment runs.
     * @param request the evaluate request.
     * @param task the task, as the agent reads it.
     * @param environment where the task runs, see {@link TaskEnvironment#resolve}.
     * @param explorerConfig config/explorer.yaml.
     * @param outcome completed when the task is over and its artifacts are saved.
     */
    public TaskVerticle(Agent agent, JsonObject request, JsonObject task, TaskEnvironment environment,
                        JsonObject explorerConfig, Platform platform, Promise<TaskOutcome> outcome){
        this.agent = agent;
        this.request = request;
        this.task = task;
        this.environment = environment;
        this.explorerConfig = explorerConfig;
        this.platform = platform;
        this.outcome = outcome;

        this.evalId = task.getString("_evalId");
        this.experimentId = request.getString("experimentId", "default");
        this.experimentFolderPath = experimentFolderPath(explorerConfig, request);
        this.experimentResultsFolderPath = experimentFolderPath + "/results";
        this.artifacts = evalId == null? null : TaskArtifacts.of(experimentFolderPath, evalId);
        this.saveTrajectory = TaskSettings.saveTrajectories(request);
    }

    /**
     * @return the folder of the experiment's artifacts.
     */
    public static String experimentFolderPath(JsonObject explorerConfig, JsonObject request){
        return explorerConfig.getString("outputArtifactsFolder") + "/" + request.getString("experimentId", "default");
    }

    @Override
    public void start(Promise<Void> started) {
        //The task runs after the deployment completes. Its end is reported through the outcome promise.
        started.complete();

        timing.markBeforeSetup();
        collectedAt = System.currentTimeMillis();
        log.info("Starting task {}", evalId);

        sockets = new TaskSockets(clientId, new JsonObject()
                .put("evalId", evalId)
                .put("taskId", task.getString("id"))
                .put("experimentId", experimentId), vertx);
        browser = new TaskBrowser(evalId, clientId, environment,
                request.getString(EvaluationTaskRequestFields.ODOSIGHT_PATH.field),
                request.getBoolean("headless", true), timing);

        //A malformed WebArena config fails the task now, rather than after it has run.
        try{
            webArenaConfig = WebArenaTaskConfig.fromTask(task);
            webArenaSettings = webArenaConfig == null? null : WebArenaEvaluationSettings.fromRequest(request);
        }catch (IllegalArgumentException e){
            setupFailed(e);
            return;
        }

        openListener()
                .compose(port->context.<Void>executeBlocking(()->{
                    browser.open(guidanceHost(port));
                    return null;
                }))
                .compose(v->awaitOdoX())
                .onSuccess(this::startTask)
                .onFailure(this::setupFailed);
    }

    @Override
    public void stop(Promise<Void> stopped) {
        //Normally the task is over and the browser already quit. If the task is undeployed while it runs, end it here,
        //staging its trajectory first so it is not lost.
        Future<Void> staged = Future.succeededFuture();
        if(!finishing){
            finishing = true;
            if(evaluation != null){
                evaluation.tryFail("Task stopped");
            }
            staged = stageTrajectory(1);
            staged.onComplete(done->outcome.tryComplete(new TaskOutcome(evalId, "failed: stopped", null)));
        }
        staged.onComplete(done->{
            if(sockets != null){
                sockets.close();
            }
            if(browser == null || browser.driver() == null){
                stopped.complete();
                return;
            }
            context.<Void>executeBlocking(()->{
                browser.close();
                return null;
            }).onComplete(closed->stopped.complete());
        });
    }

    /**
     * Listen for the task's OdoX on a port the OS picks.
     *
     * @return the port.
     */
    private Future<Integer> openListener(){
        server = vertx.createHttpServer(OdoXServerOptions.forTask(platform.guidanceConfig()));
        server.webSocketHandler(sockets::accept);
        return server.listen().map(listening->{
            log.info("Task {} listens for OdoX on port {}", evalId, listening.actualPort());
            return listening.actualPort();
        });
    }

    /**
     * @return where OdoX reaches this task: the host of the request's {@code guidanceServiceHost}, with this task's port.
     */
    String guidanceHost(int port){
        String configured = request.getString(EvaluationTaskRequestFields.ODOX_OPTIONS_GUIDANCE_SERVICE_HOST.field);
        return guidanceHost(configured, port);
    }

    static String guidanceHost(String configured, int port){
        if(configured == null || configured.isBlank()){
            throw new IllegalArgumentException("'guidanceServiceHost' is missing.");
        }
        String host = configured.contains("://")? configured.substring(configured.indexOf("://") + 3) : configured;
        int slash = host.indexOf('/');
        if(slash != -1){
            host = host.substring(0, slash);
        }
        //Drop the configured port. A bracketed IPv6 address keeps its colons.
        int colon = host.lastIndexOf(':');
        if(colon != -1 && colon > host.lastIndexOf(']')){
            host = host.substring(0, colon);
        }
        return host + ":" + port;
    }

    /**
     * @return completes with the task's OdoX client once its socket is connected, or fails after the connect timeout.
     */
    private Future<OdoClient> awaitOdoX(){
        long timeoutMs = request.getLong("odoXConnectTimeout", ODOX_CONNECT_TIMEOUT_MS);
        Promise<OdoClient> connected = Promise.promise();
        long timer = vertx.setTimer(timeoutMs, id->connected.tryFail(new TimeoutException(
                "OdoX did not connect its socket within %d ms".formatted(timeoutMs))));
        sockets.ready().onComplete(ready->{
            vertx.cancelTimer(timer);
            if(ready.succeeded()){
                connected.tryComplete(ready.result());
            }else{
                connected.tryFail(ready.cause());
            }
        });
        return connected.future();
    }

    private void startTask(OdoClient client){
        this.client = client;
        log.info("OdoX client {} connected for task {}", client.id(), evalId);

        try{
            if(agent.getMode() == null){
                throw new IllegalStateException("Unknown or unsupported agent type: " + agent);
            }

            RequestManager requestManager = client.getRequestManager();
            IAgent taskAgent = buildAgent(requestManager.getLlmScope());

            evaluation = Promise.promise();
            //Hand the promise to the RequestManager first: its completion handlers (which finish the task's token usage and
            //write <evalId>-tokens.json) must run before ours.
            requestManager.setEvaluationComplete(evaluation);
            evaluation.future().onComplete(done->{
                //Save the artifacts right away: RequestManager.taskComplete() clears the raw events right after it
                //completes the evaluation. The evaluation normally completes on this context; hop to it if not.
                if(Vertx.currentContext() == context){
                    finishTask();
                }else{
                    context.runOnContext(v->finishTask());
                }
            });

            requestManager.setLlmConfig(TaskSettings.llmConfig(request));
            requestManager.setExperimentId(experimentId);
            requestManager.setExperimentFolderPath(experimentFolderPath);
            requestManager.setEvalId(evalId);
            requestManager.setEnvironment(environment.toJson());
            requestManager.setMaxSteps(TaskSettings.maxSteps(task, agent.getMode()));
            requestManager.setTaskTiming(timing); //Already marks the setup, the RequestManager marks the execution.
            client.getEventConnectionManager().getEventProcessor().setTrajectoryCapture(saveTrajectory);
            requestManager.startTask(taskAgent, agent.getMode(), UUID.fromString(task.getString("id")), request.getLong("timeout", 180000L));
            if(browser.authReport() != null){
                requestManager.putUsageField("auth", browser.authReport());
            }
            if(agent.getMode() == ExecutionMode.UNCHARTED){
                requestManager.putUsageField("requireFullReasoning", TaskSettings.requireFullReasoning(task, agent.getMode(), request));
            }
        }catch (Throwable e){
            if(evaluation != null){
                //The task has started: end it, so its artifacts are saved as usual.
                log.error("Task {} failed after it started: {}", evalId, e.getMessage(), e);
                evaluation.tryFail(e);
            }else{
                setupFailed(e);
            }
        }
    }

    /**
     * Builds the agent that executes the task in the agent's {@link ExecutionMode}. Its LLM clients are built for this
     * task, so they count its calls and use its settings.
     */
    private IAgent buildAgent(LlmCallScope llmScope){
        JsonObject agentTask = agentTask();
        return switch (agent.getMode()){
            case CHARTED -> new ChartedAgent.Builder()
                    .mode(agent == Agent.ODO_BOT_NL? ExecutionRequest.Type.NL : ExecutionRequest.Type.PREDEFINED)
                    .pathSelectionMode(TaskSettings.pathSelectionMode(request))
                    .localizer(platform.localizer())
                    .pathsConstructor(platform.paths())
                    .taskPlanner(platform.plannerPrototype().withScope(llmScope))
                    .snippet2XML(platform.snippet2XmlPrototype().withScope(llmScope))
                    .graphDb(platform.graphDb())
                    .neo4J(platform.neo4j())
                    .sqlite(SqliteService.createProxy(vertx, SQLITE_SERVICE_ADDRESS))
                    .task(agentTask)
                    .artifactDir(experimentFolderPath)
                    .evalId(evalId)
                    .build();
            case UNCHARTED -> new Qwen38Agent.Builder()
                    .config(TaskSettings.qwenConfig(request))
                    .requireFullReasoning(TaskSettings.requireFullReasoning(task, agent.getMode(), request))
                    .llmScope(llmScope)
                    .task(agentTask)
                    .artifactDir(experimentFolderPath)
                    .evalId(evalId)
                    .build();
            case HYBRID -> new HybridAgent.Builder()
                    .task(agentTask)
                    .artifactDir(experimentFolderPath)
                    .evalId(evalId)
                    .build();
        };
    }

    /**
     * @return the task as the agent reads it. Its {@code userLocation}, where the charted agent localizes from when an
     * observation carries none, is the task's start page. A task's own {@code userLocation} often holds a host-side
     * address the browser can't reach, so it is ignored.
     */
    private JsonObject agentTask(){
        JsonObject agentTask = task.copy();
        String userLocation = agentTask.getString("userLocation");
        if(userLocation != null && !userLocation.equals(environment.startUrl())){
            log.warn("Task {}: ignoring its userLocation {}, using its start page {} instead.", evalId, userLocation, environment.startUrl());
        }
        agentTask.put("userLocation", environment.startUrl());
        return agentTask;
    }

    /**
     * The task's execution has ended: save its artifacts, score it, report its telemetry, then close everything.
     */
    private void finishTask(){
        if(finishing){
            return;
        }
        finishing = true;
        RequestManager requestManager = client.getRequestManager();

        try{
            client.getGuidanceConnectionManager().dumpHistory();
            client.getEventConnectionManager().getEventProcessor().saveRawEvents(eventsFile());
            //The events are saved, so drop the partial event file. If saving failed it is kept, so the events can be recovered.
            client.getEventConnectionManager().getEventProcessor().clearRawEvents();
            timing.markArtifactsSaved();
        }catch (Throwable e){
            //Saving the artifacts failed, e.g. out of memory. Record it and end the task anyway, or the experiment would
            //wait for this task forever.
            log.error("Task {} could not be finished: {}", evalId, e.getMessage(), e);
            requestManager.setHarnessError(e.toString());
            timing.markAfterScoring();
            requestManager.saveTaskUsage();
            close();
            return;
        }

        //Nothing that happens in the browser from here on belongs to the task.
        client.getEventConnectionManager().getEventProcessor().seal();
        client.getEventConnectionManager().stopTransmitting()
                .onFailure(err->log.debug("Task {}: OdoX did not confirm it stopped transmitting: {}", evalId, err.getMessage()));

        TaskScorer scorer = new TaskScorer(explorerConfig, request);
        Future<Void> scored = recordFinalUrl(requestManager)
                .compose(v->webArenaConfig != null? evaluateWebArena(requestManager) : Future.succeededFuture())
                .compose(v->scorer.enabled()? scoreAndReport(scorer, requestManager) : Future.succeededFuture());

        scored.onComplete(done->{
            if(done.failed()){
                Throwable e = done.cause();
                if(e instanceof IOException || e instanceof InterruptedException){
                    log.info(e.getMessage(), e);
                }else{
                    log.error("Task {} could not be scored: {}", evalId, e.getMessage(), e);
                    requestManager.setHarnessError(e.toString());
                }
            }
            //Complete the task's tokens file with the phases timed since the RequestManager first saved it.
            timing.markAfterScoring();
            requestManager.saveTaskUsage();
            close();
        });
    }

    /**
     * Record the URL of the agent's tab as {@code finalUrl} in the task's token usage file, and leave the driver on that
     * tab for the evaluators. Never fails: a browser that can't tell is logged.
     */
    private Future<Void> recordFinalUrl(RequestManager requestManager){
        return context.<Void>executeBlocking(()->{
            browser.focusAppTab();
            finalUrl = browser.driver().getCurrentUrl();
            requestManager.putUsageField("finalUrl", finalUrl);
            return null;
        }).recover(err->{
            log.warn("Task {}: could not read the final URL: {}", evalId, err.getMessage());
            return Future.succeededFuture();
        });
    }

    /**
     * Score a WebArena task with the ported WebArena evaluators on a worker thread, while the browser is still open,
     * then report its telemetry. The evaluation's own failures make the task's result ERROR; they never fail the task.
     */
    private Future<Void> evaluateWebArena(RequestManager requestManager){
        //The REST helpers use the accounts the task logged in with.
        WebArenaTaskEvaluator evaluator = new WebArenaTaskEvaluator(webArenaConfig, webArenaSettings, environment.sites(),
                environment.auth().applyTo(webArenaSettings.accounts()));
        return context.<WebArenaTaskEvaluator.Result>executeBlocking(()->{
            timing.markScoringStart();
            try{
                return evaluator.evaluate(evalId, browser.driver(), requestManager.getAgentAnswer(),
                        requestManager.isJudgedInfeasible(),
                        client.getEventConnectionManager().getEventProcessor()::droppedAfterSeal,
                        Path.of(artifactPath(WebArenaTaskEvaluator.FILE_SUFFIX)));
            }finally {
                timing.markScoringEnd();
            }
        }).map(result->{
            webArenaResult = result;
            requestManager.putUsageField("webarena", result.summary());
            if(result.judgeUsage() != null){
                requestManager.putUsageField("evaluatorUsage", result.judgeUsage());
            }
            reportWebArenaTelemetry(result, requestManager);
            return null;
        });
    }

    private void reportWebArenaTelemetry(WebArenaTaskEvaluator.Result result, RequestManager requestManager){
        timing.markAfterScoring();
        TaskInstanceResults telemetry = new TaskInstanceResults();
        telemetry.setResult(result.result());
        telemetry.setDetails(new JsonObject()
                .put("webarena", result.summary())
                .put("answer", result.answer())
                .put("finalUrl", result.finalUrl()));
        new TaskScorer(explorerConfig, request).describe(telemetry, task, experimentId,
                TaskSettings.modelDescription(agent, request), timing, requestManager.getTokenUsage());
        telemetry.setEvaluationDatasetId("webarena");
        if(webArenaConfig.taskId() != null){
            telemetry.setTaskId(String.valueOf(webArenaConfig.taskId()));
        }
        TelemetryService.createProxy(vertx, TELEMETRY_SERVICE_ADDRESS)
                .reportTaskResults(telemetry)
                .onSuccess(done->log.info("Task [{}] {} WebArena telemetry sent!", experimentId, task.getString("id")))
                .onFailure(err->log.error("Task [{}] {} WebArena telemetry failed: {}", experimentId, task.getString("id"), err.getMessage()));
    }

    /**
     * Score the task with the evaluation script on a worker thread, then report its telemetry. The report is not
     * waited for: a failure is logged and never fails the task.
     */
    private Future<Void> scoreAndReport(TaskScorer scorer, RequestManager requestManager){
        return context.<TaskInstanceResults>executeBlocking(()->{
            TaskInstanceResults telemetry = scorer.score(task, agent.getMode(), eventsFile(),
                    artifactPath(TaskArtifacts.TASK_QUERY_CONSTRUCTION_RESULT),
                    "%s/%s".formatted(experimentResultsFolderPath, task.getString("id") + "-result.json"),
                    timing);
            timing.markAfterScoring();
            scorer.describe(telemetry, task, experimentId, TaskSettings.modelDescription(agent, request), timing, requestManager.getTokenUsage());
            return telemetry;
        }).map(telemetry->{
            TelemetryService.createProxy(vertx, TELEMETRY_SERVICE_ADDRESS)
                    .reportTaskResults(telemetry)
                    .onSuccess(result->log.info("Task [{}] {} Telemetry sent!", experimentId, task.getString("id")))
                    .onFailure(err->log.error("Task [{}] {} Telemetry failed: {}", experimentId, task.getString("id"), err.getMessage()));
            return null;
        });
    }

    /**
     * Stage the task's trajectory, then quit the browser on a worker thread, close the sockets, and report the outcome.
     * The outcome waits for the trajectory to be staged, so the task's timelines are only let go of once they are on
     * disk; it does not wait for the trajectory to be committed.
     */
    private void close(){
        stageTrajectory(stagingRetryDelaysMs.length + 1).compose(staged->context.<Void>executeBlocking(()->{
            browser.close();
            return null;
        })).onComplete(done->{
            if(done.failed()){
                log.warn("Task {}: could not close the browser: {}", evalId, done.cause().getMessage());
            }
            sockets.close();
            RequestManager requestManager = client == null? null : client.getRequestManager();
            outcome.tryComplete(new TaskOutcome(evalId,
                    requestManager == null? null : requestManager.getTaskOutcome(),
                    requestManager == null? null : requestManager.getHarnessError()));
        });
    }

    /**
     * The task could not be set up. Record the failure, close what was opened, and report it.
     */
    private void setupFailed(Throwable e){
        if(finishing){
            return;
        }
        finishing = true;
        log.error("Task {} could not be set up: {}", evalId, e.getMessage(), e);
        saveSetupFailure(e);
        setupError = "failed: setup: " + e;
        stageTrajectory(stagingRetryDelaysMs.length + 1).compose(staged->context.<Void>executeBlocking(()->{
            browser.close();
            return null;
        })).onComplete(done->{
            sockets.close();
            outcome.tryComplete(new TaskOutcome(evalId, setupError, trajectoryError));
        });
    }

    /**
     * How a task that could not be set up ended, or null.
     */
    private String setupError;

    /**
     * Why the trajectory of a task without a RequestManager (setup failure) could not be staged, or null.
     */
    private String trajectoryError;

    /**
     * Stage the task's trajectory (see {@link TrajectoryWriter}), then hand it to the {@link TrajectoryStore} to commit.
     * Staging is retried with backoff, holding the task, so a full disk slows the experiment down rather than losing
     * trajectories. If it still fails, the task reports {@code trajectory not staged} as its harness error.
     *
     * @param attempts how many times to try.
     * @return completes once the trajectory is staged, or staging has been given up on; never fails.
     */
    private Future<Void> stageTrajectory(int attempts){
        if(!saveTrajectory){
            return Future.succeededFuture();
        }
        TrajectoryStore store = TrajectoryStore.get();
        if(store == null){
            trajectoryNotStaged("the trajectory store is not running");
            return Future.succeededFuture();
        }
        Path folder = store.stagingFolder(experimentId, TaskArtifacts.fileStem(evalId == null? "unknown" : evalId));
        return attemptStaging(store, folder, 1, attempts);
    }

    private Future<Void> attemptStaging(TrajectoryStore store, Path folder, int attempt, int attempts){
        RequestManager requestManager = client == null? null : client.getRequestManager();
        OnlineEventProcessor processor = client == null? null : client.getEventConnectionManager().getEventProcessor();
        return context.<Void>executeBlocking(()->{
            TrajectoryWriter.stage(taskRun(requestManager), processor, requestManager == null? null : requestManager.getStepLedger(), folder);
            return null;
        }).compose(staged->{
            log.info("Task {}: trajectory staged in {}.", evalId, folder);
            if(requestManager != null){
                requestManager.putUsageField("trajectory", new JsonObject().put("staged", true)
                        .put("folder", store.dbDir().relativize(folder).toString().replace('\\', '/')));
                requestManager.saveTaskUsage();
            }
            store.commit(folder).onFailure(err->log.error("Task {}: trajectory stays staged in {}: {}", evalId, folder, err.getMessage()));
            return Future.<Void>succeededFuture();
        }, err->{
            if(attempt >= attempts){
                log.error("Task {}: could not stage its trajectory after {} attempt(s), it is lost: {}", evalId, attempt, err.getMessage(), err);
                trajectoryNotStaged(err.toString());
                return Future.succeededFuture();
            }
            long delay = stagingRetryDelaysMs[Math.min(attempt - 1, stagingRetryDelaysMs.length - 1)];
            log.warn("Task {}: could not stage its trajectory (attempt {}/{}), retrying in {} ms: {}", evalId, attempt, attempts, delay, err.getMessage());
            Promise<Void> retried = Promise.promise();
            vertx.setTimer(Math.max(1, delay), id->attemptStaging(store, folder, attempt + 1, attempts).onComplete(retried));
            return retried.future();
        });
    }

    private void trajectoryNotStaged(String reason){
        String error = "trajectory not staged: " + reason;
        RequestManager requestManager = client == null? null : client.getRequestManager();
        if(requestManager != null){
            requestManager.setHarnessError(requestManager.getHarnessError() == null? error : requestManager.getHarnessError() + "; " + error);
            requestManager.putUsageField("trajectory", new JsonObject().put("staged", false).put("error", reason));
            requestManager.saveTaskUsage();
        }else{
            trajectoryError = error;
        }
    }

    /**
     * @return what the trajectory records of the task besides its timelines and steps.
     */
    private TrajectoryWriter.TaskRun taskRun(RequestManager requestManager){
        JsonObject report = null;
        if(artifacts != null){
            Path reportFile = artifacts.file(WebArenaTaskEvaluator.FILE_SUFFIX);
            try{
                if(Files.isRegularFile(reportFile)){
                    report = new JsonObject(Files.readString(reportFile));
                }
            }catch (IOException | RuntimeException e){
                log.warn("Task {}: could not read {} for its trajectory: {}", evalId, reportFile, e.getMessage());
            }
        }
        boolean requireFullReasoning;
        try{
            requireFullReasoning = agent.getMode() != null && TaskSettings.requireFullReasoning(task, agent.getMode(), request);
        }catch (IllegalArgumentException e){
            requireFullReasoning = false;
        }
        return new TrajectoryWriter.TaskRun(
                experimentId,
                evalId,
                task,
                agent.getMode() == null? null : agent.getMode().name(),
                agent.name(),
                safeModelDescription(),
                agentConfig(),
                requireFullReasoning,
                BuildInfo.odobotCommit(),
                BuildInfo.odoxVersion(request.getString(EvaluationTaskRequestFields.ODOSIGHT_PATH.field)),
                collectedAt,
                System.currentTimeMillis(),
                requestManager == null? setupError : requestManager.getTaskOutcome(),
                requestManager == null? null : requestManager.getHarnessError(),
                finalUrl,
                requestManager == null? null : requestManager.getAgentAnswer(),
                requestManager != null && requestManager.isJudgedInfeasible(),
                webArenaConfig == null? null : webArenaConfig.taskId(),
                webArenaResult == null? null : webArenaResult.summary(),
                report);
    }

    private String safeModelDescription(){
        try{
            return TaskSettings.modelDescription(agent, request);
        }catch (RuntimeException e){
            return null;
        }
    }

    /**
     * @return the agent's settings from the evaluate request ({@code llm} and, for the uncharted agent, {@code qwen}),
     * without API keys.
     */
    private JsonObject agentConfig(){
        JsonObject config = new JsonObject();
        for(String key: new String[]{"llm", "qwen"}){
            JsonObject settings = request.getJsonObject(key);
            if(settings != null){
                JsonObject copy = settings.copy();
                copy.remove("api_key");
                config.put(key, copy);
            }
        }
        return config;
    }

    /**
     * Record a task that could not be set up in {@code <evalId>-tokens.json}, like a task that ran, so the experiment
     * summary counts it as failed. It writes no event file, so the task is run again when the experiment is resumed.
     */
    private void saveSetupFailure(Throwable e){
        if(artifacts == null){
            return;
        }
        JsonObject usage = new JsonObject()
                .put("evalId", evalId)
                .put("experimentId", experimentId)
                .put("executionId", null)
                .put("mode", agent.getMode() == null? null : agent.getMode().name())
                .put("outcome", "failed: setup: " + e)
                .mergeIn(new TokenUsageRecord().toJson())
                .put("environment", environment.toJson())
                .put("timing", timing.toJson());
        if(browser != null && browser.authReport() != null){
            usage.put("auth", browser.authReport());
        }
        try{
            Files.createDirectories(artifacts.folder());
            Files.writeString(artifacts.file(TaskArtifacts.TOKENS), usage.encodePrettily());
        }catch (IOException | RuntimeException ex){
            log.error("Could not record the setup failure of task {}: {}", evalId, ex.getMessage());
        }
    }

    private String eventsFile(){
        return artifactPath(TaskArtifacts.EVENTS);
    }

    /**
     * @return the path of one of the task's artifacts, {@code <experiment folder>/<evalId><suffix>}, see {@link TaskArtifacts}.
     */
    private String artifactPath(String suffix){
        if(artifacts == null){
            throw new IllegalStateException("Task has no _evalId to name its artifacts after.");
        }
        return artifacts.file(suffix).toString();
    }
}
