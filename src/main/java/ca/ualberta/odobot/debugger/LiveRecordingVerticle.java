package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.explorer.EvaluationTaskRequestFields;
import ca.ualberta.odobot.guidance.OdoClient;
import ca.ualberta.odobot.guidance.OdoXServerOptions;
import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.TaskSockets;
import ca.ualberta.odobot.guidance.TaskTiming;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.task.Platform;
import ca.ualberta.odobot.task.TaskBrowser;
import ca.ualberta.odobot.task.TaskEnvironment;
import ca.ualberta.odobot.task.TaskVerticle;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * A person records a trajectory by hand: OdoBot opens Firefox on the grid with OdoX, logs in to the sites, opens the
 * start page and starts OdoX's transmission, with no agent. Only once OdoX confirms the transmission is the session
 * ready, and the person told to connect to the browser (noVNC). Their clicks, typing and the page's reactions then
 * build the charted timeline as they would for an agent, and go to the Trajectory Debugger's active timeline.
 *
 * <p>Like {@link TaskVerticle}, it listens for its own OdoX on a port of its own and reuses the task's browser set-up
 * ({@link TaskBrowser}) and sockets ({@link TaskSockets}).</p>
 *
 * <p>Entities: each top-level timeline entity gets a session id ({@code liveId}). Only the latest one can still change,
 * so the previous one is <em>frozen</em> when a new one arrives: its row is written to its own folder
 * ({@link EntityFolder}) and the debugger takes it over. The latest is frozen when the session ends. What happens is
 * published on {@link RecordingSessions#EVENTS}; requests are answered on {@link RecordingSessions#address}.</p>
 */
public class LiveRecordingVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(LiveRecordingVerticle.class);

    static final long ODOX_CONNECT_TIMEOUT_MS = 60_000L;

    static final long DIAGNOSTICS_TIMEOUT_MS = 4_000L;

    /**
     * How often an entity that keeps changing (a data entry being typed, an effect gathering DOM effects) is reported.
     */
    static final long UPDATE_THROTTLE_MS = 300L;

    /**
     * How often the browser's WebDriver session is touched while a person drives it. A Selenium grid ends a session
     * that gets no command for {@code SE_NODE_SESSION_TIMEOUT} (300 s by default), which would close the browser.
     */
    static final long KEEP_ALIVE_MS = 60_000L;

    private final String sessionId;
    private final JsonObject request;
    private final TaskEnvironment environment;
    private final Platform platform;
    private final Path sessionDir;
    private final JsonObject session;
    private final Promise<JsonObject> ready;

    private final UUID clientId = UUID.randomUUID();

    /**
     * The id OdoX files the session's transmissions under, see {@link ca.ualberta.odobot.guidance.connectionmanagers.EventConnectionManager#startTransmitting(String)}.
     */
    private final String pathsRequestId = UUID.randomUUID().toString();
    private TaskSockets sockets;
    private TaskBrowser browser;
    private HttpServer server;
    private OdoClient client;
    private OnlineEventProcessor processor;
    private PipelineDiagnostics diagnostics;
    private MessageConsumer<JsonObject> consumer;
    private long tickTimer = -1;
    private long keepAliveTimer = -1;

    private final Map<TimelineEntity, Long> liveIds = new IdentityHashMap<>();
    private final Map<Long, TimelineEntity> entities = new java.util.HashMap<>();
    private final Set<Long> frozen = new HashSet<>();
    private final Set<Long> pendingUpdates = new HashSet<>();
    private TimelineEntity latest;
    private long nextLiveId = 0;
    private int views = 0;

    private boolean transmitting = false;
    private boolean ending = false;

    /**
     * Freezing is ordered: each entity's row is written after the previous one's.
     */
    private Future<Void> freezing = Future.succeededFuture();

    /**
     * @param session the session as registered with {@link RecordingSessions}: sessionId, viewerUrl, debuggerUrl, startUrl.
     * @param request the debugging request: the evaluate request's browser fields, see {@code POST /api/debug/record}.
     * @param ready   completes with the session once OdoX confirmed the transmission, or fails if set-up failed.
     */
    public LiveRecordingVerticle(JsonObject session, JsonObject request, TaskEnvironment environment, Platform platform,
                                 Path debuggerDir, Promise<JsonObject> ready) {
        this.sessionId = session.getString("sessionId");
        this.session = session;
        this.request = request;
        this.environment = environment;
        this.platform = platform;
        this.sessionDir = debuggerDir.resolve("sessions").resolve(sessionId);
        this.ready = ready;
    }

    private String label() {
        return "debug-" + sessionId;
    }

    @Override
    public void start(Promise<Void> started) {
        started.complete();
        try {
            Files.createDirectories(sessionDir.resolve("entities"));
        } catch (Exception e) {
            setupFailed(e);
            return;
        }

        sockets = new TaskSockets(clientId, new JsonObject().put("evalId", label()).put("sessionId", sessionId), vertx);
        browser = new TaskBrowser(label(), clientId, environment,
                request.getString(EvaluationTaskRequestFields.ODOSIGHT_PATH.field), request.getBoolean("headless", false),
                new TaskTiming());

        server = vertx.createHttpServer(OdoXServerOptions.forTask(platform.guidanceConfig()));
        server.webSocketHandler(sockets::accept);
        server.listen()
                .map(listening -> {
                    log.info("Recording session {} listens for OdoX on port {}", sessionId, listening.actualPort());
                    return TaskVerticle.guidanceHost(request.getString(EvaluationTaskRequestFields.ODOX_OPTIONS_GUIDANCE_SERVICE_HOST.field),
                            listening.actualPort());
                })
                .compose(guidanceHost -> context.<Void>executeBlocking(() -> {
                    browser.open(guidanceHost);
                    return null;
                }))
                .compose(v -> awaitOdoX())
                .compose(this::startRecording)
                .onSuccess(v -> {
                    JsonObject info = session.copy().put("state", "recording").put("transmitting", true)
                            .put("startedAt", System.currentTimeMillis());
                    RecordingSessions.update(sessionId, info);
                    vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "session-started")
                            .put("sessionId", sessionId).put("session", info));
                    log.info("Recording session {} is recording. Connect to the browser: {}  Watch the timeline: {}",
                            sessionId, session.getString("viewerUrl"), session.getString("debuggerUrl"));
                    ready.tryComplete(info);
                })
                .onFailure(this::setupFailed);
    }

    private Future<OdoClient> awaitOdoX() {
        long timeoutMs = request.getLong("odoXConnectTimeout", ODOX_CONNECT_TIMEOUT_MS);
        Promise<OdoClient> connected = Promise.promise();
        long timer = vertx.setTimer(timeoutMs, id -> connected.tryFail(new TimeoutException(
                "OdoX did not connect its socket within %d ms".formatted(timeoutMs))));
        sockets.ready().onComplete(done -> {
            vertx.cancelTimer(timer);
            if (done.succeeded()) {
                connected.tryComplete(done.result());
            } else {
                connected.tryFail(done.cause());
            }
        });
        return connected.future();
    }

    /**
     * Wire the timeline to the debugger, then start OdoX's transmission.
     */
    private Future<Void> startRecording(OdoClient client) {
        this.client = client;
        processor = client.getEventConnectionManager().getEventProcessor();
        processor.setTrajectoryCapture(true);
        processor.streamRawEventsTo(sessionDir.resolve("events.json.part"));
        processor.setOnEntity(this::onNewEntity);
        processor.setOnEntityUpdated(this::onEntityUpdated);

        diagnostics = new PipelineDiagnostics(processor);
        client.addPageListener(diagnostics::onPage);
        tickTimer = vertx.setPeriodic(500, id -> diagnostics.tick());
        keepAliveTimer = vertx.setPeriodic(KEEP_ALIVE_MS, id -> context.<Void>executeBlocking(() -> {
            if (browser.driver() != null) {
                browser.driver().getWindowHandles();
            }
            return null;
        }, false).onFailure(err -> log.warn("Recording session {}: the browser did not answer: {}", sessionId, err.getMessage())));

        consumer = vertx.eventBus().consumer(RecordingSessions.address(sessionId), this::onRequest);
        return client.getEventConnectionManager().startTransmitting(pathsRequestId).onSuccess(v -> transmitting = true);
    }

    private void setupFailed(Throwable e) {
        log.error("Recording session {} could not be set up: {}", sessionId, e.getMessage(), e);
        ready.tryFail(e);
        RecordingSessions.release(sessionId);
        vertx.undeploy(deploymentID());
    }

    // ---------------------------------------------------------------- entities

    private void onNewEntity(TimelineEntity entity) {
        if (liveIds.containsKey(entity)) {
            return;
        }
        long liveId = nextLiveId++;
        liveIds.put(entity, liveId);
        entities.put(liveId, entity);
        TimelineEntity previous = latest;
        latest = entity;
        //The new entity's events are attributed right after it is added; the update that follows reports its summary.
        publishEntity(entity, true);
        if (previous != null) {
            freeze(previous);
        }
    }

    private void onEntityUpdated(TimelineEntity entity) {
        Long liveId = liveIds.get(entity);
        if (liveId == null || frozen.contains(liveId) || !pendingUpdates.add(liveId)) {
            return;
        }
        vertx.setTimer(UPDATE_THROTTLE_MS, id -> {
            pendingUpdates.remove(liveId);
            if (!frozen.contains(liveId)) {
                publishEntity(entity, false);
            }
        });
    }

    private void publishEntity(TimelineEntity entity, boolean isNew) {
        JsonObject summary;
        try {
            summary = EntitySummaries.of(TrajectoryWriter.chartedRow(entity, 0, processor, null, "live", sessionId).row());
        } catch (Exception e) {
            log.warn("Recording session {}: could not summarize a {}: {}", sessionId, entity.symbol(), e.getMessage());
            summary = new JsonObject().put("kind", entity.getClass().getSimpleName()).put("symbol", entity.symbol()).put("label", "");
        }
        vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "entity").put("sessionId", sessionId)
                .put("liveId", liveIds.get(entity)).put("isNew", isNew).put("summary", summary));
    }

    /**
     * Write the entity's row to its folder, then hand it to the debugger. The entity no longer changes: a newer one
     * arrived, or the session is ending.
     */
    private Future<Void> freeze(TimelineEntity entity) {
        long liveId = liveIds.get(entity);
        if (!frozen.add(liveId)) {
            return freezing;
        }
        Path folder = sessionDir.resolve("entities").resolve(String.valueOf(liveId));
        freezing = freezing.recover(err -> Future.succeededFuture()).compose(v -> context.<JsonObject>executeBlocking(() -> {
            TrajectoryWriter.EntityRow row = TrajectoryWriter.chartedRow(entity, 0, processor, folder, "live", sessionId);
            EntityFolder.write(folder, row);
            return EntitySummaries.of(EntityFolder.read(folder).row());
        }, false)).map(summary -> {
            vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "frozen").put("sessionId", sessionId)
                    .put("liveId", liveId).put("folder", folder.toString()).put("summary", summary));
            return (Void) null;
        }).onFailure(err -> log.error("Recording session {}: could not save entity {}: {}", sessionId, liveId, err.getMessage(), err));
        return freezing;
    }

    // ---------------------------------------------------------------- requests

    private void onRequest(Message<JsonObject> message) {
        JsonObject body = message.body();
        String action = body.getString("action", "");
        Future<JsonObject> reply = switch (action) {
            case "row" -> row(body.getLong("liveId", -1L));
            case "transmit" -> transmit(body.getBoolean("on", true));
            case "diagnostics" -> diagnostics();
            case "end" -> end().map(v -> new JsonObject().put("ended", sessionId));
            default -> Future.failedFuture("Unknown action '" + action + "'");
        };
        reply.onSuccess(message::reply).onFailure(err -> message.fail(500, String.valueOf(err.getMessage())));
    }

    /**
     * Build a live entity's row now, for its detail view or an export, in a folder of its own. Built on the session's
     * context, where the entity changes, so it is consistent.
     */
    private Future<JsonObject> row(long liveId) {
        TimelineEntity entity = entities.get(liveId);
        if (entity == null) {
            return Future.failedFuture("Session " + sessionId + " has no entity " + liveId);
        }
        Path folder = sessionDir.resolve("views").resolve(liveId + "-" + (views++));
        try {
            TrajectoryWriter.EntityRow row = TrajectoryWriter.chartedRow(entity, 0, processor, folder, "live", sessionId);
            EntityFolder.write(folder, row);
            return Future.succeededFuture(new JsonObject().put("folder", folder.toString()));
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    private Future<JsonObject> transmit(boolean on) {
        if (ending) {
            return Future.failedFuture("The session is ending");
        }
        Future<Void> done = on ? client.getEventConnectionManager().startTransmitting(pathsRequestId)
                : client.getEventConnectionManager().stopTransmitting();
        return done.map(v -> {
            transmitting = on;
            RecordingSessions.update(sessionId, new JsonObject().put("transmitting", on));
            vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "transmitting")
                    .put("sessionId", sessionId).put("on", on));
            return new JsonObject().put("transmitting", on);
        });
    }

    private Future<JsonObject> diagnostics() {
        JsonObject odobot = diagnostics.report()
                .put("sessionId", sessionId)
                .put("clientId", clientId.toString())
                .put("socket", client.getSocket().isConnected() ? "connected" : "disconnected")
                .put("pageReady", client.pageId())
                .put("transmitting", transmitting);
        return client.getEventConnectionManager().requestDiagnostics(DIAGNOSTICS_TIMEOUT_MS)
                .map(reply -> new JsonObject().put("odobot", odobot).put("odox", reply.copy().put("supported", true)))
                .recover(err -> Future.succeededFuture(new JsonObject().put("odobot", odobot)
                        .put("odox", new JsonObject().put("supported", false).put("error", err.getMessage()))));
    }

    /**
     * Stop the transmission, freeze the latest entity, save the raw events, then undeploy, which closes the browser.
     */
    private Future<Void> end() {
        if (ending) {
            return Future.succeededFuture();
        }
        ending = true;
        Future<Void> stopped = transmitting ? client.getEventConnectionManager().stopTransmitting()
                .recover(err -> Future.succeededFuture()) : Future.succeededFuture();
        return stopped
                .compose(v -> latest == null ? Future.<Void>succeededFuture() : freeze(latest))
                .compose(v -> context.<Void>executeBlocking(() -> {
                    processor.seal();
                    processor.saveRawEvents(sessionDir.resolve("events.json").toString());
                    return null;
                }, false))
                .recover(err -> {
                    log.warn("Recording session {}: could not save its events: {}", sessionId, err.getMessage());
                    return Future.succeededFuture();
                })
                .onComplete(done -> {
                    vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "session-ended").put("sessionId", sessionId));
                    log.info("Recording session {} ended.", sessionId);
                    vertx.setTimer(100, id -> vertx.undeploy(deploymentID()));
                });
    }

    @Override
    public void stop(Promise<Void> stopped) {
        if (tickTimer != -1) {
            vertx.cancelTimer(tickTimer);
        }
        if (keepAliveTimer != -1) {
            vertx.cancelTimer(keepAliveTimer);
        }
        if (consumer != null) {
            consumer.unregister();
        }
        RecordingSessions.release(sessionId);
        if (!ending && client != null) {
            //Undeployed without ending, e.g. at shutdown: tell the debugger, which drops what it can't freeze.
            vertx.eventBus().publish(RecordingSessions.EVENTS, new JsonObject().put("type", "session-ended").put("sessionId", sessionId));
        }
        if (sockets != null) {
            sockets.close();
        }
        if (browser == null || browser.driver() == null) {
            stopped.complete();
            return;
        }
        context.<Void>executeBlocking(() -> {
            browser.close();
            return null;
        }).onComplete(closed -> stopped.complete());
    }
}
