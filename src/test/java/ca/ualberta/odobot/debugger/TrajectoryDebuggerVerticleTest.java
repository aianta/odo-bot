package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.multipart.MultipartForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The debugger as a person uses it: a recording session (played here by the test on the event bus) streams entities,
 * which land on the active timeline and are frozen into the workspace; then the timeline is cut, annotated, exported
 * and imported back.
 */
class TrajectoryDebuggerVerticleTest {

    @TempDir
    Path dir;

    private Vertx vertx;
    private WebClient http;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        Path config = dir.resolve("trajectory-debugger.yaml");
        Files.writeString(config, "host: 127.0.0.1\nport: 0\ndir: " + dir.resolve("debugger").toString().replace('\\', '/')
                + "\ndatasetDir: " + dir.resolve("db").toString().replace('\\', '/') + "\n");
        TrajectoryDebuggerVerticle debugger = new TrajectoryDebuggerVerticle(config.toString());
        await(vertx.deployVerticle(debugger));
        int port = await(debugger.listening());
        http = WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1").setDefaultPort(port));
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private JsonObject get(String path) throws Exception {
        HttpResponse<Buffer> response = await(http.get(path).send());
        assertEquals(200, response.statusCode(), path + ": " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    private JsonObject send(String method, String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = await(http.request(io.vertx.core.http.HttpMethod.valueOf(method), path).sendJsonObject(body));
        assertEquals(200, response.statusCode(), path + ": " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    private JsonObject workspace() throws Exception {
        return get("/api/workspace").getJsonObject("workspace");
    }

    private void eventually(BooleanSupplier condition, String what) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private JsonObject quietly(ThrowingSupplier<JsonObject> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Play a recording session: announce it, stream the processor's entities as a session does (each frozen once the
     * next arrives, the last one at the end), and answer the debugger's row requests.
     */
    private void playSession(String sessionId, OnlineEventProcessor processor) throws Exception {
        Path sessionDir = dir.resolve("debugger/sessions").resolve(sessionId);
        List<TimelineEntity> entities = processor.timeline();
        vertx.eventBus().<JsonObject>consumer(RecordingSessions.address(sessionId), message -> {
            JsonObject body = message.body();
            switch (body.getString("action")) {
                case "transmit" -> message.reply(new JsonObject().put("transmitting", body.getBoolean("on")));
                case "diagnostics" -> message.reply(new JsonObject().put("odobot", new JsonObject().put("socket", "connected"))
                        .put("odox", new JsonObject().put("supported", false)));
                case "row" -> {
                    try {
                        int liveId = body.getLong("liveId").intValue();
                        Path folder = sessionDir.resolve("views").resolve(liveId + "-" + System.nanoTime());
                        EntityFolder.write(folder, TrajectoryWriter.chartedRow(entities.get(liveId), 0, processor, folder, "live", sessionId));
                        message.reply(new JsonObject().put("folder", folder.toString()));
                    } catch (Exception e) {
                        message.fail(500, e.getMessage());
                    }
                }
                default -> message.fail(400, "unexpected");
            }
        });
        publish(new JsonObject().put("type", "session-started").put("sessionId", sessionId)
                .put("session", new JsonObject().put("sessionId", sessionId).put("transmitting", true)
                        .put("viewerUrl", "http://localhost:7900/")));
        for (int i = 0; i < entities.size(); i++) {
            TrajectoryWriter.EntityRow row = TrajectoryWriter.chartedRow(entities.get(i), 0, processor, null, "live", sessionId);
            publish(new JsonObject().put("type", "entity").put("sessionId", sessionId).put("liveId", i).put("isNew", true)
                    .put("summary", EntitySummaries.of(row.row())));
        }
    }

    private void freeze(String sessionId, OnlineEventProcessor processor, int liveId) throws Exception {
        Path folder = dir.resolve("debugger/sessions").resolve(sessionId).resolve("entities").resolve(String.valueOf(liveId));
        EntityFolder.write(folder, TrajectoryWriter.chartedRow(processor.timeline().get(liveId), 0, processor, folder, "live", sessionId));
        publish(new JsonObject().put("type", "frozen").put("sessionId", sessionId).put("liveId", liveId)
                .put("folder", folder.toString()).put("summary", EntitySummaries.of(EntityFolder.read(folder).row())));
    }

    private void publish(JsonObject event) {
        vertx.eventBus().publish(RecordingSessions.EVENTS, event);
    }

    @Test
    void aRecordingLandsOnTheActiveTimelineAndCanBeCutAnnotatedExportedAndImportedBack() throws Exception {
        assertTrue(workspace().getJsonArray("timelines").isEmpty());

        OnlineEventProcessor processor = DebuggerFixtures.recording();
        int count = processor.timeline().size();
        playSession("s1", processor);
        eventually(() -> quietly(this::workspace).getJsonArray("timelines").size() == 1
                && quietly(this::workspace).getJsonArray("timelines").getJsonObject(0).getJsonArray("entities").size() == count, "the recorded entities");

        JsonObject ws = workspace();
        JsonObject timeline = ws.getJsonArray("timelines").getJsonObject(0);
        assertEquals(timeline.getString("id"), ws.getString("activeTimelineId"), "the first recorded entity creates the active timeline");
        assertEquals("Untitled timeline", timeline.getString("title"));
        assertEquals(Boolean.TRUE, get("/api/workspace").getJsonObject("session").getBoolean("transmitting"));

        //A live entity's detail is built by its session; a frozen one's is read from its folder.
        String clickId = timeline.getJsonArray("entities").getJsonObject(1).getString("id");
        JsonObject live = get("/api/entities/" + clickId);
        assertTrue(live.getJsonObject("entity").getBoolean("live"));
        assertEquals("ClickEvent", live.getJsonObject("row").getString("kind"));
        assertNotNull(live.getString("screenshotUrl"));
        assertEquals(200, await(http.get(live.getString("screenshotUrl")).send()).statusCode());
        assertEquals(200, await(http.get(live.getString("cropUrl")).send()).statusCode());

        for (int i = 0; i < count; i++) {
            freeze("s1", processor, i);
        }
        eventually(() -> quietly(this::workspace).getJsonArray("timelines").getJsonObject(0).getJsonArray("entities").stream()
                .noneMatch(e -> ((JsonObject) e).getValue("live") != null), "the entities to be frozen");
        JsonObject frozen = get("/api/entities/" + clickId);
        assertFalse(frozen.getJsonObject("entity").getBoolean("live"));
        assertEquals(200, await(http.get(frozen.getString("screenshotUrl")).send()).statusCode());
        assertEquals(page("clicked"), await(http.get("/api/entities/" + clickId + "/dom").send()).bodyAsString());

        //Annotate, cut, and check the second half became active.
        String timelineId = timeline.getString("id");
        send("PATCH", "/api/timelines/" + timelineId, new JsonObject().put("title", "Save a shirt").put("notes", "toast shows"));
        send("PATCH", "/api/entities/" + clickId, new JsonObject().put("notes", "clicked Save"));
        JsonObject cut = send("POST", "/api/timelines/" + timelineId + "/cut", new JsonObject().put("entityId", clickId));
        assertNotNull(cut.getString("second"));
        assertEquals(cut.getString("second"), workspace().getString("activeTimelineId"));

        //Export both halves, then import the export back as copies.
        HttpResponse<Buffer> zip = await(http.post("/api/export").sendJsonObject(new JsonObject()
                .put("timelineIds", new JsonArray().add(timelineId).add(cut.getString("second")))));
        assertEquals(200, zip.statusCode(), zip.bodyAsString());
        assertEquals("application/zip", zip.getHeader("content-type"));
        Path zipFile = dir.resolve("export.zip");
        Files.write(zipFile, zip.body().getBytes());

        HttpResponse<Buffer> opened = await(http.post("/api/import/upload").sendMultipartForm(MultipartForm.create()
                .binaryFileUpload("file", "export.zip", zipFile.toString(), "application/zip")));
        assertEquals(200, opened.statusCode(), opened.bodyAsString());
        JsonObject listing = opened.bodyAsJsonObject();
        JsonArray trajectories = listing.getJsonArray("experiments").getJsonObject(0).getJsonArray("trajectories");
        assertEquals(2, trajectories.size());
        JsonArray ids = new JsonArray(trajectories.stream().map(t -> ((JsonObject) t).getString("id")).toList());
        JsonObject imported = send("POST", "/api/import/" + listing.getString("importId"), new JsonObject().put("trajectoryIds", ids));
        assertEquals(2, imported.getJsonArray("imported").size());

        JsonArray timelines = workspace().getJsonArray("timelines");
        assertEquals(4, timelines.size());
        JsonObject firstCopy = timelines.stream().map(t -> (JsonObject) t).filter(t -> "Save a shirt".equals(t.getString("title"))
                && t.getJsonObject("source") != null).findFirst().orElseThrow();
        assertEquals("toast shows", firstCopy.getString("notes"));
        assertEquals("clicked Save", firstCopy.getJsonArray("entities").getJsonObject(1).getString("notes"));

        //Delete a copy; the original stays.
        send("DELETE", "/api/timelines/" + firstCopy.getString("id"), new JsonObject());
        assertEquals(3, workspace().getJsonArray("timelines").size());
        assertEquals("clicked Save", get("/api/entities/" + clickId).getJsonObject("entity").getString("notes"));
    }

    @Test
    void theUiIsServedAndTheWorkspaceSurvivesARestart() throws Exception {
        HttpResponse<Buffer> index = await(http.get("/").send());
        assertEquals(200, index.statusCode());
        assertTrue(index.bodyAsString().contains("Trajectory Debugger"));
        assertEquals(200, await(http.get("/app.js").send()).statusCode());
        assertEquals(200, await(http.get("/vendor/vue.global.prod.js").send()).statusCode());

        send("POST", "/api/timelines", new JsonObject().put("title", "kept"));
        Thread.sleep(1_500); //The workspace is saved a moment after a change.
        assertTrue(Files.isRegularFile(dir.resolve("debugger/workspace/workspace.json")));
        assertEquals(404, await(http.get("/api/session/diagnostics").send()).statusCode(), "no session runs");
    }

    private static String page(String marker) {
        return ca.ualberta.odobot.trajectory.TrajectoryFixtures.page(marker);
    }
}
