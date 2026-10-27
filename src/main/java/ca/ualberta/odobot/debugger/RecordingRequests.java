package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.explorer.EvaluationTaskRequestFields;
import ca.ualberta.odobot.task.Platform;
import ca.ualberta.odobot.task.TaskEnvironment;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The debugging request: record a trajectory by hand.
 *
 * <pre>
 * POST   /api/debug/record              start a session; answers once OdoX is transmitting, with where to connect
 * GET    /api/debug/record              the session running, if any
 * DELETE /api/debug/record/:sessionId   end it: the browser closes, its timeline stays in the debugger
 * </pre>
 *
 * <p>The body takes the evaluate request's browser fields ({@code odoSightPath}, {@code guidanceServiceHost},
 * {@code firefoxDockerGridURL}, {@code targetHosts}, {@code headless} (default false), {@code odoXConnectTimeout}, and
 * optionally the LogUI fields), plus a {@code startUrl} and/or one {@code task} whose {@code environment} names the
 * start page, sites and session login, as a WebArena scheduler request does; {@code startUrl} wins. {@code viewerUrl}
 * is the noVNC link of the grid's Firefox, as reachable from the person's browser.</p>
 */
public final class RecordingRequests {

    private static final Logger log = LoggerFactory.getLogger(RecordingRequests.class);

    public static final String DEFAULT_VIEWER_URL = "http://localhost:7900/?autoconnect=1&resize=scale&password=secret";
    public static final String DEFAULT_DEBUGGER_URL = "http://localhost:8077";

    private RecordingRequests() {
    }

    /**
     * @param guidanceConfig  config/guidance.yaml, or null if it has not loaded.
     * @param debuggerConfig  config/trajectory-debugger.yaml: {@code dir}, {@code viewerUrl}, {@code publicUrl}.
     */
    public static void start(RoutingContext rc, Vertx vertx, JsonObject guidanceConfig, JsonObject debuggerConfig) {
        JsonObject body;
        try {
            body = rc.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null) {
            error(rc, 400, "The body must be a JSON object.");
            return;
        }
        if (guidanceConfig == null) {
            error(rc, 503, "OdoBot's services are still starting, try again shortly.");
            return;
        }
        for (EvaluationTaskRequestFields field : new EvaluationTaskRequestFields[]{EvaluationTaskRequestFields.ODOSIGHT_PATH,
                EvaluationTaskRequestFields.ODOX_OPTIONS_GUIDANCE_SERVICE_HOST}) {
            if (body.getString(field.field) == null) {
                error(rc, 400, "'%s' is missing.".formatted(field.field));
                return;
            }
        }

        JsonObject request = body.copy();
        //OdoX's options page wants LogUI settings, though nothing is sent to LogUI.
        request.put("logUIHost", request.getString("logUIHost", "logui.invalid:8000"));
        request.put("logUIUsername", request.getString("logUIUsername", "unused"));
        request.put("logUIPassword", request.getString("logUIPassword", "unused"));
        request.put("headless", request.getBoolean("headless", false));

        JsonObject task = body.getValue("task") instanceof JsonObject t ? t.copy() : new JsonObject();
        //A task entry of an evaluate request wraps the task in its format's field, e.g. {"odoBotNL": {...}}.
        if (task.size() == 1 && task.getValue("odoBotNL") instanceof JsonObject inner) {
            task = inner.copy();
        }
        if (body.getString("startUrl") != null) {
            JsonObject environment = task.getValue("environment") instanceof JsonObject e ? e : new JsonObject();
            task.put("environment", environment.put("startUrl", body.getString("startUrl")));
        }
        TaskEnvironment environment;
        try {
            environment = TaskEnvironment.resolve(task, request);
        } catch (IllegalArgumentException e) {
            error(rc, 400, e.getMessage());
            return;
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        JsonObject session = new JsonObject()
                .put("sessionId", sessionId)
                .put("state", "starting")
                .put("viewerUrl", body.getString("viewerUrl", debuggerConfig.getString("viewerUrl", DEFAULT_VIEWER_URL)))
                .put("debuggerUrl", debuggerConfig.getString("publicUrl", DEFAULT_DEBUGGER_URL))
                .put("startUrl", environment.startUrl())
                .put("gridUrl", environment.gridUrl());
        if (!RecordingSessions.claim(session)) {
            rc.response().setStatusCode(409).putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "A recording session is already running; end it first.")
                            .put("session", RecordingSessions.current()).encode());
            return;
        }

        Promise<JsonObject> ready = Promise.promise();
        Path dir = Path.of(debuggerConfig.getString("dir", "db/debugger"));
        LiveRecordingVerticle verticle = new LiveRecordingVerticle(session, request, environment, Platform.current(guidanceConfig), dir, ready);
        DeploymentOptions options = new DeploymentOptions()
                .setWorkerPoolName("debug-recording-pool")
                .setWorkerPoolSize(4)
                .setMaxWorkerExecuteTime(1)
                .setMaxWorkerExecuteTimeUnit(TimeUnit.HOURS);
        log.info("Starting recording session {} at {}", sessionId, environment.startUrl());
        vertx.deployVerticle(verticle, options).onFailure(err -> {
            RecordingSessions.release(sessionId);
            ready.tryFail(err);
        });
        ready.future()
                .onSuccess(info -> rc.response().putHeader("content-type", "application/json").end(info.copy()
                        .put("message", "OdoX is recording. Connect to the browser at %s, and watch the timeline at %s."
                                .formatted(info.getString("viewerUrl"), info.getString("debuggerUrl")))
                        .encodePrettily()))
                .onFailure(err -> error(rc, 500, "The recording session could not be set up: " + err.getMessage()));
    }

    public static void current(RoutingContext rc) {
        JsonObject current = RecordingSessions.current();
        rc.response().putHeader("content-type", "application/json")
                .end((current == null ? new JsonObject() : current).encodePrettily());
    }

    public static void end(RoutingContext rc, Vertx vertx) {
        String sessionId = rc.pathParam("sessionId");
        JsonObject current = RecordingSessions.current();
        if (current == null || !current.getString("sessionId").equals(sessionId)) {
            error(rc, 404, "No recording session " + sessionId + " is running.");
            return;
        }
        vertx.eventBus().<JsonObject>request(RecordingSessions.address(sessionId), new JsonObject().put("action", "end"),
                        new DeliveryOptions().setSendTimeout(120_000))
                .onSuccess(reply -> rc.response().putHeader("content-type", "application/json").end(reply.body().encode()))
                .onFailure(err -> error(rc, 500, err.getMessage()));
    }

    private static void error(RoutingContext rc, int status, String message) {
        rc.response().setStatusCode(status).putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", message).encode());
    }
}
