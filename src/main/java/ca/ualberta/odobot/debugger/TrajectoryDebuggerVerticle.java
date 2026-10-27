package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.common.ConfigurableVerticle;
import ca.ualberta.odobot.trajectory.BuildInfo;
import ca.ualberta.odobot.trajectory.TrajectoryStore;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Trajectory Debugger: a small web app (Vue, served from {@code trajectory-debugger/} on the classpath) over the
 * timelines a person records by hand ({@code POST /api/debug/record} on the evaluation API), imports from trajectory
 * databases, cuts, annotates and exports. It owns the {@link Workspace}, saved under {@code <dir>/workspace/}, and
 * hears recording sessions on {@link RecordingSessions#EVENTS}.
 *
 * <p>Config, {@code config/trajectory-debugger.yaml}: {@code host}, {@code port} (8077), {@code dir}
 * ({@code db/debugger}), {@code publicUrl} and {@code viewerUrl}.</p>
 */
public class TrajectoryDebuggerVerticle extends ConfigurableVerticle {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryDebuggerVerticle.class);

    private static final long REQUEST_TIMEOUT_MS = 30_000;
    private static final long IMPORT_SOURCE_TTL_MS = 60 * 60 * 1000;

    private Vertx core;
    private Path dir;
    private Path entitiesDir;
    private Path workspaceFile;
    private Workspace workspace;
    private JsonObject session;
    private long sessionVersion = 0;
    private long saveTimer = -1;

    /**
     * Databases picked to import from, by id, until they are imported from or expire.
     */
    private final Map<String, ImportSource> importSources = new HashMap<>();

    private record ImportSource(String id, Path db, Path baseDir, String label, Path cleanup, long createdAt) {
    }

    private final String configFilePath;
    private final Promise<Integer> listening = Promise.promise();

    public TrajectoryDebuggerVerticle() {
        this("config/trajectory-debugger.yaml");
    }

    /**
     * @param configFilePath the yaml config, e.g. a test's.
     */
    public TrajectoryDebuggerVerticle(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * @return completes with the port the debugger listens on, once it does.
     */
    public Future<Integer> listening() {
        return listening.future();
    }

    @Override
    public String configFilePath() {
        return configFilePath;
    }

    @Override
    public String serviceName() {
        return "Trajectory Debugger";
    }

    @Override
    public Completable onStart() {
        core = vertx.getDelegate();
        dir = Path.of(_config.getString("dir", "db/debugger"));
        entitiesDir = dir.resolve("workspace").resolve("entities");
        workspaceFile = dir.resolve("workspace").resolve("workspace.json");
        try {
            Files.createDirectories(entitiesDir);
            for (String transient_ : List.of("imports", "exports", "uploads")) {
                EntityFolder.delete(dir.resolve(transient_));
            }
            workspace = loadWorkspace();
        } catch (Exception e) {
            log.error("The Trajectory Debugger could not open {}: {}", dir, e.getMessage(), e);
            workspace = new Workspace();
        }
        core.eventBus().<JsonObject>consumer(RecordingSessions.EVENTS, this::onSessionEvent);
        session = RecordingSessions.current();

        Router router = Router.router(core);
        router.route().handler(BodyHandler.create().setUploadsDirectory(dir.resolve("uploads").toString())
                .setBodyLimit(4L * 1024 * 1024 * 1024).setDeleteUploadedFilesOnEnd(true));
        Router api = Router.router(core);
        routes(api);
        router.route("/api/*").subRouter(api);
        router.route("/*").handler(StaticHandler.create("trajectory-debugger").setCachingEnabled(false).setIndexPage("index.html"));

        int port = _config.getInteger("port", 8077);
        core.createHttpServer().requestHandler(router).listen(port, _config.getString("host", "0.0.0.0"))
                .onSuccess(server -> {
                    log.info("Trajectory Debugger on port {}, workspace in {}", server.actualPort(), dir);
                    listening.tryComplete(server.actualPort());
                })
                .onFailure(err -> {
                    log.error("The Trajectory Debugger could not listen on port {}: {}", port, err.getMessage());
                    listening.tryFail(err);
                });
        core.setPeriodic(10 * 60 * 1000, id -> expireImportSources());
        return Completable.complete();
    }

    private void routes(Router api) {
        api.route(HttpMethod.GET, "/workspace").handler(this::getWorkspace);
        api.route(HttpMethod.POST, "/timelines").handler(rc -> change(rc, () -> workspace.createTimeline(body(rc).getString("title")).id()));
        api.route(HttpMethod.PATCH, "/timelines/:id").handler(this::patchTimeline);
        api.route(HttpMethod.DELETE, "/timelines/:id").handler(this::deleteTimeline);
        api.route(HttpMethod.POST, "/timelines/:id/cut").handler(this::cut);
        api.route(HttpMethod.GET, "/entities/:id").handler(this::getEntity);
        api.route(HttpMethod.GET, "/entities/:id/dom").handler(this::getDom);
        api.route(HttpMethod.PATCH, "/entities/:id").handler(rc -> change(rc, () -> {
            workspace.setEntityNotes(rc.pathParam("id"), body(rc).getString("notes"));
            return rc.pathParam("id");
        }));
        api.route(HttpMethod.GET, "/files/*").handler(this::getFile);
        api.route(HttpMethod.GET, "/session").handler(rc -> json(rc, session == null ? new JsonObject() : session));
        api.route(HttpMethod.DELETE, "/session").handler(rc -> toSession(rc, new JsonObject().put("action", "end"), 120_000));
        api.route(HttpMethod.POST, "/recording").handler(rc -> toSession(rc,
                new JsonObject().put("action", "transmit").put("on", body(rc).getBoolean("on", true)), REQUEST_TIMEOUT_MS));
        api.route(HttpMethod.GET, "/session/diagnostics").handler(rc -> toSession(rc, new JsonObject().put("action", "diagnostics"), 15_000));
        api.route(HttpMethod.POST, "/export").handler(this::export);
        api.route(HttpMethod.GET, "/import/dataset").handler(this::openDataset);
        api.route(HttpMethod.POST, "/import/upload").handler(this::openUpload);
        api.route(HttpMethod.POST, "/import/:importId").handler(this::importTimelines);
        api.route(HttpMethod.DELETE, "/import/:importId").handler(rc -> {
            closeImportSource(rc.pathParam("importId"));
            json(rc, new JsonObject().put("closed", rc.pathParam("importId")));
        });
        api.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            int status = failure instanceof IllegalArgumentException ? 400 : rc.statusCode() > 0 ? rc.statusCode() : 500;
            if (status >= 500) {
                log.error("Trajectory Debugger: {}", failure == null ? "error" : failure.getMessage(), failure);
            }
            error(rc, status, failure == null ? "Error " + status : String.valueOf(failure.getMessage()));
        });
    }

    // ---------------------------------------------------------------- workspace

    private Workspace loadWorkspace() throws Exception {
        if (!Files.isRegularFile(workspaceFile)) {
            return new Workspace();
        }
        Workspace loaded = Workspace.fromJson(new JsonObject(Files.readString(workspaceFile, StandardCharsets.UTF_8)));
        int dropped = loaded.dropLive();
        if (dropped > 0) {
            log.warn("Dropped {} entities of a recording that ended without saving them.", dropped);
        }
        log.info("Trajectory Debugger workspace loaded: {} timelines.", loaded.timelines().size());
        return loaded;
    }

    /**
     * Save the workspace soon, once changes settle.
     */
    private void saveSoon() {
        if (saveTimer != -1) {
            return;
        }
        saveTimer = core.setTimer(1_000, id -> {
            saveTimer = -1;
            JsonObject state = workspace.toJson();
            core.executeBlocking(() -> {
                Path temp = workspaceFile.resolveSibling("workspace.json.tmp");
                Files.writeString(temp, state.encodePrettily(), StandardCharsets.UTF_8);
                Files.move(temp, workspaceFile, StandardCopyOption.REPLACE_EXISTING);
                return null;
            }, true).onFailure(err -> log.error("Could not save the debugger workspace: {}", err.getMessage()));
        });
    }

    private String etag() {
        return workspace.version() + "-" + sessionVersion;
    }

    private void getWorkspace(RoutingContext rc) {
        String etag = etag();
        if (etag.equals(rc.request().getParam("since"))) {
            rc.response().setStatusCode(304).end();
            return;
        }
        json(rc, new JsonObject()
                .put("etag", etag)
                .put("session", session)
                .put("workspace", workspace.toJson()));
    }

    /**
     * Apply a change to the workspace, save it, and answer with what changed.
     */
    private void change(RoutingContext rc, java.util.function.Supplier<String> action) {
        String id = action.get();
        saveSoon();
        json(rc, new JsonObject().put("id", id).put("etag", etag()));
    }

    private void patchTimeline(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject body = body(rc);
        change(rc, () -> {
            if (body.containsKey("title")) {
                workspace.rename(id, body.getString("title"));
            }
            if (body.containsKey("notes")) {
                workspace.setNotes(id, body.getString("notes"));
            }
            if (body.containsKey("active")) {
                if (Boolean.TRUE.equals(body.getBoolean("active"))) {
                    workspace.setActive(id);
                } else if (id.equals(workspace.activeTimelineId())) {
                    workspace.setActive(null);
                }
            }
            return id;
        });
    }

    private void deleteTimeline(RoutingContext rc) {
        List<Workspace.Entity> removed = workspace.delete(rc.pathParam("id"));
        saveSoon();
        core.executeBlocking(() -> {
            for (Workspace.Entity entity : removed) {
                EntityFolder.delete(entitiesDir.resolve(entity.id()));
            }
            return null;
        }, false);
        json(rc, new JsonObject().put("deleted", rc.pathParam("id")).put("etag", etag()));
    }

    private void cut(RoutingContext rc) {
        Workspace.Cut cut = workspace.cut(rc.pathParam("id"), body(rc).getString("entityId"));
        saveSoon();
        json(rc, new JsonObject().put("first", cut.first().id()).put("second", cut.second() == null ? null : cut.second().id())
                .put("message", cut.second() == null ? "Nothing follows that entity, so there is nothing to cut off." : null)
                .put("etag", etag()));
    }

    // ---------------------------------------------------------------- recording sessions

    private void onSessionEvent(Message<JsonObject> message) {
        JsonObject event = message.body();
        String sessionId = event.getString("sessionId");
        switch (event.getString("type", "")) {
            case "session-started" -> {
                session = event.getJsonObject("session");
                sessionVersion++;
            }
            case "transmitting" -> {
                if (session != null && sessionId.equals(session.getString("sessionId"))) {
                    session.put("transmitting", event.getBoolean("on"));
                    sessionVersion++;
                }
            }
            case "entity" -> {
                workspace.onLiveEntity(sessionId, event.getLong("liveId"), event.getJsonObject("summary"), event.getBoolean("isNew", false));
                saveSoon();
            }
            case "frozen" -> onFrozen(sessionId, event.getLong("liveId"), Path.of(event.getString("folder")), event.getJsonObject("summary"));
            case "session-ended" -> {
                if (session != null && sessionId.equals(session.getString("sessionId"))) {
                    session = null;
                    sessionVersion++;
                }
                //Anything still live was never saved; give the freezes in flight a moment to land first.
                core.setTimer(5_000, id -> {
                    int dropped = workspace.dropLive(sessionId);
                    if (dropped > 0) {
                        log.warn("Dropped {} entities recording session {} never saved.", dropped, sessionId);
                        saveSoon();
                    }
                });
            }
            default -> log.debug("Ignoring session event {}", event.encode());
        }
    }

    private void onFrozen(String sessionId, long liveId, Path folder, JsonObject summary) {
        Workspace.Entity entity = workspace.liveEntity(sessionId, liveId).orElse(null);
        core.executeBlocking(() -> {
            if (entity == null) {
                EntityFolder.delete(folder); //Deleted while it was recorded.
            } else {
                EntityFolder.move(folder, entitiesDir.resolve(entity.id()));
            }
            return null;
        }, true).onSuccess(v -> {
            if (entity != null) {
                workspace.frozen(entity, summary);
                saveSoon();
            }
        }).onFailure(err -> log.error("Could not keep entity {} of session {}: {}", liveId, sessionId, err.getMessage()));
    }

    private void toSession(RoutingContext rc, JsonObject request, long timeoutMs) {
        if (session == null) {
            error(rc, 404, "No recording session is running. Start one with POST /api/debug/record on OdoBot's evaluation API (port 8076).");
            return;
        }
        core.eventBus().<JsonObject>request(RecordingSessions.address(session.getString("sessionId")), request,
                        new DeliveryOptions().setSendTimeout(timeoutMs))
                .onSuccess(reply -> json(rc, reply.body()))
                .onFailure(err -> error(rc, 502, err.getMessage()));
    }

    // ---------------------------------------------------------------- entities

    /**
     * @return the folder holding the entity's row: its own if frozen, or one its session builds now if live.
     */
    private Future<Path> entityFolder(Workspace.Entity entity) {
        if (!entity.isLive()) {
            return Future.succeededFuture(entitiesDir.resolve(entity.id()));
        }
        Workspace.LiveRef live = entity.live();
        return core.eventBus().<JsonObject>request(RecordingSessions.address(live.sessionId()),
                        new JsonObject().put("action", "row").put("liveId", live.liveId()), new DeliveryOptions().setSendTimeout(REQUEST_TIMEOUT_MS))
                .map(reply -> Path.of(reply.body().getString("folder")));
    }

    private void getEntity(RoutingContext rc) {
        String id = rc.pathParam("id");
        Workspace.Entity entity = workspace.entity(id).orElse(null);
        if (entity == null) {
            error(rc, 404, "No entity " + id);
            return;
        }
        Workspace.Timeline timeline = workspace.timelineOf(id).orElse(null);
        int position = timeline == null ? -1 : timeline.entities().stream().map(Workspace.Entity::id).toList().indexOf(id);
        entityFolder(entity).compose(folder -> core.executeBlocking(() -> EntityFolder.read(folder), false)).onSuccess(read -> {
            JsonObject row = read.row().copy();
            for (String column : List.of("details", "bbox", "crop_box")) {
                Object value = row.getValue(column);
                if (value instanceof String text && (text.startsWith("{") || text.startsWith("["))) {
                    row.put(column, text.startsWith("{") ? new JsonObject(text) : new JsonArray(text));
                }
            }
            json(rc, new JsonObject()
                    .put("entity", new JsonObject().put("id", entity.id()).put("notes", entity.notes()).put("live", entity.isLive())
                            .put("summary", entity.summary()))
                    .put("timelineId", timeline == null ? null : timeline.id())
                    .put("position", position)
                    .put("row", row)
                    .put("screenshotUrl", fileUrl(read.file("screenshot_path")))
                    .put("cropUrl", fileUrl(read.file("crop_path")))
                    .put("screenshotMissing", row.getString("screenshot_path") != null && read.file("screenshot_path") == null)
                    .put("hasDom", read.domHtml() != null));
        }).onFailure(err -> error(rc, 502, "Could not read entity " + id + ": " + err.getMessage()));
    }

    private void getDom(RoutingContext rc) {
        Workspace.Entity entity = workspace.entity(rc.pathParam("id")).orElse(null);
        if (entity == null) {
            error(rc, 404, "No entity " + rc.pathParam("id"));
            return;
        }
        entityFolder(entity).compose(folder -> core.executeBlocking(() -> EntityFolder.read(folder), false)).onSuccess(read -> {
            if (read.domHtml() == null) {
                error(rc, 404, "This entity has no page snapshot.");
                return;
            }
            //Plain text: the UI shows it in a sandboxed frame, so the page's scripts never run here.
            rc.response().putHeader("content-type", "text/plain; charset=utf-8").end(read.domHtml());
        }).onFailure(err -> error(rc, 502, err.getMessage()));
    }

    private String fileUrl(Path file) {
        if (file == null) {
            return null;
        }
        return "/api/files/" + dir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void getFile(RoutingContext rc) {
        String relative = rc.pathParam("*");
        Path root = dir.toAbsolutePath().normalize();
        Path file = relative == null ? null : root.resolve(relative).normalize();
        if (file == null || !file.startsWith(root) || !file.getFileName().toString().endsWith(".png") || !Files.isRegularFile(file)) {
            error(rc, 404, "No such file.");
            return;
        }
        rc.response().putHeader("content-type", "image/png").sendFile(file.toString());
    }

    // ---------------------------------------------------------------- export

    private void export(RoutingContext rc) {
        JsonArray ids = body(rc).getJsonArray("timelineIds", new JsonArray());
        if (ids.isEmpty()) {
            error(rc, 400, "Choose at least one timeline to download.");
            return;
        }
        List<Workspace.Timeline> timelines = new ArrayList<>();
        for (Object id : ids) {
            Workspace.Timeline timeline = workspace.timeline(String.valueOf(id)).orElse(null);
            if (timeline == null) {
                error(rc, 404, "No timeline " + id);
                return;
            }
            timelines.add(timeline);
        }
        //Every entity's folder: live ones are built by their session now.
        List<Future<List<Path>>> folders = new ArrayList<>();
        for (Workspace.Timeline timeline : timelines) {
            List<Future<Path>> rows = timeline.entities().stream().map(this::entityFolder).toList();
            folders.add(Future.all(rows).map(all -> rows.stream().map(Future::result).toList()));
        }
        Instant at = Instant.now();
        Path workDir = dir.resolve("exports").resolve(UUID.randomUUID().toString());
        Future.all(folders).compose(all -> core.executeBlocking(() -> {
            List<TimelineExporter.Timeline> export = new ArrayList<>();
            for (int i = 0; i < timelines.size(); i++) {
                List<EntityFolder.Entity> rows = new ArrayList<>();
                for (Path folder : folders.get(i).result()) {
                    rows.add(EntityFolder.read(folder));
                }
                export.add(new TimelineExporter.Timeline(timelines.get(i), rows));
            }
            return TimelineExporter.zip(export, workDir, at, new TimelineExporter.Build(BuildInfo.odobotCommit(),
                    BuildInfo.odoxVersion(_config.getString("odoSightPath", "./odo-sight.xpi"))));
        }, false)).onSuccess(zip -> rc.response()
                .putHeader("content-type", "application/zip")
                .putHeader("content-disposition", "attachment; filename=\"" + zip.getFileName() + "\"")
                .sendFile(zip.toString())
                .onComplete(sent -> core.executeBlocking(() -> {
                    EntityFolder.delete(workDir);
                    return null;
                }, false))
        ).onFailure(err -> {
            log.error("Export failed: {}", err.getMessage(), err);
            error(rc, 500, "Export failed: " + err.getMessage());
        });
    }

    // ---------------------------------------------------------------- import

    /**
     * Open OdoBot's experiment dataset to import from: a snapshot the trajectory store writes (never the live file),
     * or, if the store isn't running, a copy of {@code db/trajectories.db}.
     */
    private void openDataset(RoutingContext rc) {
        String id = UUID.randomUUID().toString();
        Path folder = dir.resolve("imports").resolve(id);
        Path dataset = Path.of(_config.getString("datasetDir", "db"));
        TrajectoryStore store = TrajectoryStore.get();
        Future<Path> db = store != null && store.canWrite()
                ? store.snapshot().map(Path::of)
                : core.executeBlocking(() -> {
                    Path source = dataset.resolve("trajectories.db");
                    if (!Files.isRegularFile(source)) {
                        throw new IllegalArgumentException("OdoBot has no trajectory dataset (" + source + ").");
                    }
                    Files.createDirectories(folder);
                    return Files.copy(source, folder.resolve("trajectories.db"));
                }, false);
        db.compose(path -> core.executeBlocking(() -> {
            JsonObject listing = TimelineImporter.list(path);
            importSources.put(id, new ImportSource(id, path, dataset, "OdoBot dataset",
                    path.startsWith(folder) ? folder : path, System.currentTimeMillis()));
            return listing.put("importId", id).put("label", "OdoBot dataset");
        }, false)).onSuccess(listing -> json(rc, listing)).onFailure(err -> error(rc, 500, err.getMessage()));
    }

    /**
     * Open an uploaded export (.zip) or trajectory database (.db) to import from.
     */
    private void openUpload(RoutingContext rc) {
        List<FileUpload> uploads = rc.fileUploads();
        if (uploads.isEmpty()) {
            error(rc, 400, "Upload a .zip (a Trajectory Debugger export) or a .db (a trajectory database).");
            return;
        }
        FileUpload upload = uploads.get(0);
        String id = UUID.randomUUID().toString();
        Path folder = dir.resolve("imports").resolve(id);
        String name = upload.fileName() == null ? "upload" : upload.fileName();
        core.executeBlocking(() -> {
            Path db;
            if (name.toLowerCase().endsWith(".zip")) {
                try (InputStream in = Files.newInputStream(Path.of(upload.uploadedFileName()))) {
                    db = TimelineImporter.unzip(in, folder);
                }
            } else if (name.toLowerCase().endsWith(".db")) {
                Files.createDirectories(folder);
                db = Files.copy(Path.of(upload.uploadedFileName()), folder.resolve("trajectories.db"));
            } else {
                throw new IllegalArgumentException("Upload a .zip or a .db file, not " + name + ".");
            }
            JsonObject listing = TimelineImporter.list(db);
            importSources.put(id, new ImportSource(id, db, db.getParent(), name, folder, System.currentTimeMillis()));
            return listing.put("importId", id).put("label", name);
        }, false).onSuccess(listing -> json(rc, listing)).onFailure(err -> {
            try {
                EntityFolder.delete(folder);
            } catch (Exception ignored) {
            }
            error(rc, err instanceof IllegalArgumentException ? 400 : 500, err.getMessage());
        });
    }

    private void importTimelines(RoutingContext rc) {
        ImportSource source = importSources.get(rc.pathParam("importId"));
        if (source == null) {
            error(rc, 404, "That import has expired; open the source again.");
            return;
        }
        List<String> ids = body(rc).getJsonArray("trajectoryIds", new JsonArray()).stream().map(String::valueOf).toList();
        if (ids.isEmpty()) {
            error(rc, 400, "Choose at least one trajectory to import.");
            return;
        }
        core.executeBlocking(() -> TimelineImporter.importInto(workspace, source.db(), source.baseDir(), source.label(), ids,
                entitiesDir::resolve), false).onSuccess(timelines -> {
            closeImportSource(source.id());
            saveSoon();
            json(rc, new JsonObject().put("imported", new JsonArray(timelines.stream().map(Workspace.Timeline::id).toList()))
                    .put("etag", etag()));
        }).onFailure(err -> error(rc, err instanceof IllegalArgumentException ? 400 : 500, "Import failed: " + err.getMessage()));
    }

    private void closeImportSource(String id) {
        ImportSource source = importSources.remove(id);
        if (source == null) {
            return;
        }
        core.executeBlocking(() -> {
            if (Files.isDirectory(source.cleanup())) {
                EntityFolder.delete(source.cleanup());
            } else {
                Files.deleteIfExists(source.cleanup());
            }
            return null;
        }, false).onFailure(err -> log.warn("Could not clean up import {}: {}", id, err.getMessage()));
    }

    private void expireImportSources() {
        long now = System.currentTimeMillis();
        for (String id : Set.copyOf(importSources.keySet())) {
            if (now - importSources.get(id).createdAt() > IMPORT_SOURCE_TTL_MS) {
                closeImportSource(id);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject body(RoutingContext rc) {
        try {
            JsonObject body = rc.body().asJsonObject();
            return body == null ? new JsonObject() : body;
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static void json(RoutingContext rc, JsonObject body) {
        rc.response().putHeader("content-type", "application/json").end(body.encode());
    }

    private static void error(RoutingContext rc, int status, String message) {
        if (rc.response().ended()) {
            return;
        }
        rc.response().setStatusCode(status).putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", message).encode());
    }
}
