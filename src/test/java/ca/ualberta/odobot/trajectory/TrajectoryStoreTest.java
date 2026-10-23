package ca.ualberta.odobot.trajectory;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.guidance.StepLedger;
import ca.ualberta.odobot.semanticflow.model.Observation;
import ca.ualberta.odobot.semanticflow.model.Screenshot;
import ca.ualberta.odobot.semanticflow.model.TimelineEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static ca.ualberta.odobot.trajectory.TrajectoryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TrajectoryStoreTest {

    @TempDir
    Path db;

    private Vertx vertx;
    private TrajectoryStore store;
    private long[] retryDelays;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        retryDelays = TrajectoryStore.commitRetryDelaysMs;
        TrajectoryStore.commitRetryDelaysMs = new long[]{1, 1};
        store = new TrajectoryStore(vertx, db);
        await(store.start());
    }

    @AfterEach
    void stop() throws Exception {
        TrajectoryStore.commitRetryDelaysMs = retryDelays;
        store.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    /**
     * Stage a one-step task that clicks Save.
     */
    private Path stage(String evalId) throws Exception {
        OnlineEventProcessor processor = new OnlineEventProcessor();
        processor.setUnchartedTimelineEnabled(true);
        processor.setTrajectoryCapture(true);
        StepLedger ledger = new StepLedger();
        processor.process(startObservation(png(400, 300, Color.WHITE)));
        TimelineEntity start = processor.unchartedTimeline().get(0);
        ledger.emitted(1, "uncharted_step", TrajectoryWriterTest.trace(1, start, new JsonObject().put("action", "left_click")
                .put("x", 140).put("y", 65)), null);
        ledger.sent(1, "exec-1", 2_000, processor.lastEventIndex());
        processor.process(click(2_010, "clicked", png(400, 300, Color.BLUE)));
        processor.process(domEffect(2_020, "add", "/html/body/div[2]", "Saved", "saved"));
        Observation after = new Observation(Observation.Trigger.UNCHARTED_ACTION, null, BASE_URI,
                Screenshot.fromBase64(png(400, 300, Color.GRAY)), 3_000, 1, "exec-1");
        processor.addUnchartedObservation(after);
        ledger.observed(1, after, 3_000, processor.lastEventIndex());

        Path folder = store.stagingFolder("exp-1", TrajectoryWriter.fileStem(evalId));
        TrajectoryWriter.stage(TrajectoryWriterTest.run(evalId), processor, ledger, folder);
        return folder;
    }

    private Connection read() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db.resolve("trajectories.db") + "?open_mode=1");
    }

    private long count(String sql) throws Exception {
        try (Connection connection = read(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private List<String> column(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = read(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    @Test
    void aStagedTrajectoryIsCommittedWithItsScreenshots() throws Exception {
        Path folder = stage("7|OdoBotNL|0b1c");
        await(store.commit(folder));

        assertEquals(1, count("SELECT COUNT(*) FROM trajectories"));
        assertEquals(1, count("SELECT success FROM trajectories"), "PASS");
        assertEquals(5, count("SELECT COUNT(*) FROM timeline_entities"), "3 charted (start, click, effect) and 2 uncharted");
        assertEquals(1, count("SELECT COUNT(*) FROM agent_steps WHERE reasoning <> '' AND input_observation IS NOT NULL AND result_observation IS NOT NULL"));
        assertEquals(2, count("SELECT COUNT(*) FROM dom_snapshots"));
        assertEquals(0, column("PRAGMA foreign_key_check").size());
        assertEquals(List.of("spatial"), column("SELECT method FROM interaction_thoughts WHERE kind = 'ClickEvent'"),
                "the view prefers the spatial link");

        assertFalse(Files.exists(folder), "staging is deleted once committed");
        for (String path : column("SELECT screenshot_path FROM timeline_entities WHERE screenshot_path IS NOT NULL")) {
            assertTrue(Files.isRegularFile(db.resolve(path)), path);
        }
        assertTrue(Files.isRegularFile(db.resolve(column("SELECT action_crop_path FROM agent_steps").get(0))));
        assertEquals(1, store.status().getLong("committed"));
        assertEquals(0, store.status().getInteger("staged"));
    }

    @Test
    void aRerunReplacesTheEarlierTrajectory() throws Exception {
        await(store.commit(stage("7|OdoBotNL|0b1c")));
        String first = column("SELECT id FROM trajectories").get(0);
        await(store.commit(stage("7|OdoBotNL|0b1c")));

        assertEquals(1, count("SELECT COUNT(*) FROM trajectories"));
        assertNotEquals(first, column("SELECT id FROM trajectories").get(0));
        assertEquals(5, count("SELECT COUNT(*) FROM timeline_entities"), "the earlier rows cascade away");
        assertEquals(0, column("PRAGMA foreign_key_check").size());
    }

    @Test
    void whatAStoppedRunLeftStagedIsCommittedOnceAtTheNextStart() throws Exception {
        Path folder = stage("8|OdoBotNL|0b1d");
        store.close();

        store = new TrajectoryStore(vertx, db);
        await(store.start());
        assertEquals(1, count("SELECT COUNT(*) FROM trajectories"));
        assertFalse(Files.exists(folder));

        store.close();
        store = new TrajectoryStore(vertx, db);
        await(store.start());
        assertEquals(1, count("SELECT COUNT(*) FROM trajectories"), "committed exactly once");
    }

    @Test
    void aFailedCommitStaysStagedUntilItCanBeCommitted() throws Exception {
        Path folder = stage("9|OdoBotNL|0b1e");
        Path staged = folder.resolve(TrajectoryStore.STAGED_FILE);
        String good = Files.readString(staged);
        Files.writeString(staged, "{ not json");

        assertThrows(Exception.class, () -> await(store.commit(folder)));
        assertTrue(Files.exists(staged), "a trajectory that can't be committed is never deleted");
        assertEquals(1, store.status().getInteger("staged"));
        assertNotNull(store.status().getString("lastError"));
        assertEquals(List.of("9-OdoBotNL-0b1e"), store.status("exp-1").getJsonArray("stagedEvalIds").getList());
        assertEquals(List.of(), store.status("other").getJsonArray("stagedEvalIds").getList());

        Files.writeString(staged, good);
        assertEquals(1, await(store.importStaged()));
        assertEquals(1, count("SELECT COUNT(*) FROM trajectories"));
    }

    @Test
    void concurrentCommitsAllLand() throws Exception {
        List<Path> folders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            folders.add(stage(i + "|OdoBotNL|c" + i));
        }
        List<Future<Void>> commits = folders.parallelStream().map(store::commit).toList();
        await(Future.all(commits));

        assertEquals(10, count("SELECT COUNT(*) FROM trajectories"));
        assertEquals(0, column("PRAGMA foreign_key_check").size());
        try (Stream<Path> left = Files.walk(db.resolve("staging"))) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().equals(TrajectoryStore.STAGED_FILE)));
        }
    }

    @Test
    void aSecondOdoBotOnTheSameDatabaseRefusesToWrite() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.resolve("trajectories.db"));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE dataset_meta SET value = '" + new JsonObject().put("id", "other-host/1/1")
                    .put("heartbeatAt", System.currentTimeMillis()).encode() + "' WHERE key = 'writer'");
        }
        TrajectoryStore second = new TrajectoryStore(vertx, db);
        Exception refused = assertThrows(Exception.class, () -> await(second.start()));
        assertTrue(refused.getMessage().contains("another OdoBot"), refused.getMessage());
        assertFalse(second.canWrite());
        second.close();
    }

    @Test
    void aSnapshotIsAConsistentCopy() throws Exception {
        await(store.commit(stage("7|OdoBotNL|0b1c")));
        Path snapshot = Path.of(await(store.snapshot()));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM trajectories")) {
            row.next();
            assertEquals(1, row.getLong(1));
        }
    }

    @Test
    void vacuumDropsPageSnapshotsNoTrajectoryUses() throws Exception {
        await(store.commit(stage("7|OdoBotNL|0b1c")));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.resolve("trajectories.db"));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO dom_snapshots(sha256, html, bytes) VALUES ('orphan', '<html></html>', 13)");
        }
        await(store.vacuum());
        assertEquals(0, count("SELECT COUNT(*) FROM dom_snapshots WHERE sha256 = 'orphan'"));
        assertEquals(2, count("SELECT COUNT(*) FROM dom_snapshots"));
    }
}
