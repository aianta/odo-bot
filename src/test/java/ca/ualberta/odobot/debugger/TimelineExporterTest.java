package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.guidance.OnlineEventProcessor;
import ca.ualberta.odobot.trajectory.TrajectoryDatabase;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import ca.ualberta.odobot.trajectory.TrajectoryWriterTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class TimelineExporterTest {

    @TempDir
    Path dir;

    static final Instant AT = Instant.parse("2026-10-27T15:30:12Z");
    static final TimelineExporter.Build BUILD = new TimelineExporter.Build("abc123", "0.1+test");

    static List<TimelineExporter.Timeline> exportable(Workspace workspace, Path entitiesDir, List<String> timelineIds) throws Exception {
        List<TimelineExporter.Timeline> timelines = new ArrayList<>();
        for (String id : timelineIds) {
            Workspace.Timeline timeline = workspace.timeline(id).orElseThrow();
            List<EntityFolder.Entity> rows = new ArrayList<>();
            for (Workspace.Entity entity : timeline.entities()) {
                rows.add(EntityFolder.read(entitiesDir.resolve(entity.id())));
            }
            timelines.add(new TimelineExporter.Timeline(timeline, rows));
        }
        return timelines;
    }

    static List<Map<String, Object>> rows(Path db, String sql) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db + "?open_mode=1");
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    row.put(meta.getColumnLabel(c), result.getObject(c));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The charted rows a task's trajectory saves for the processor's timeline, committed to a database of its own.
     */
    private Path agentRunDatabase(OnlineEventProcessor processor) throws Exception {
        Path staging = dir.resolve("agent-staging");
        var staged = TrajectoryWriter.stage(TrajectoryWriterTest.run("1|OdoBotNL|x"), processor, null, staging);
        Path db = dir.resolve("agent.db");
        try (TrajectoryDatabase database = TrajectoryDatabase.open(db)) {
            database.insert(staged);
            database.commit();
        }
        return db;
    }

    @Test
    void anExportHoldsEveryColumnATaskTrajectorySavesForTheSameEntitiesPlusNotes() throws Exception {
        OnlineEventProcessor processor = DebuggerFixtures.recording();
        Workspace workspace = new Workspace();
        Path entities = dir.resolve("entities");
        Workspace.Timeline timeline = DebuggerFixtures.record(workspace, processor, entities);
        workspace.rename(timeline.id(), "Save a shirt");
        workspace.setNotes(timeline.id(), "the save toast shows");
        workspace.setEntityNotes(timeline.entities().get(1).id(), "clicked Save");

        Path root = dir.resolve("export");
        TimelineExporter.write(exportable(workspace, entities, List.of(timeline.id())), root, AT, BUILD);
        Path exported = root.resolve("trajectories.db");

        List<Map<String, Object>> expected = rows(agentRunDatabase(processor), """
                SELECT e.*, d.html FROM timeline_entities e LEFT JOIN dom_snapshots d ON d.id = e.dom_snapshot_id
                WHERE e.timeline = 'charted' ORDER BY e.position""");
        List<Map<String, Object>> actual = rows(exported, """
                SELECT e.*, d.html FROM timeline_entities e LEFT JOIN dom_snapshots d ON d.id = e.dom_snapshot_id
                ORDER BY e.position""");

        assertEquals(timeline.entities().size(), actual.size(), "observation, click, data entry, effect, network event");
        assertEquals(expected.size(), actual.size());
        Set<String> different = Set.of("id", "trajectory_id", "dom_snapshot_id", "screenshot_path", "crop_path", "notes");
        for (int i = 0; i < expected.size(); i++) {
            for (String column : expected.get(i).keySet()) {
                if (!different.contains(column)) {
                    assertEquals(expected.get(i).get(column), actual.get(i).get(column), "row " + i + " column " + column);
                }
            }
            //Screenshots: same files, by name and bytes, under the export's own folder.
            for (String column : List.of("screenshot_path", "crop_path")) {
                Object want = expected.get(i).get(column), got = actual.get(i).get(column);
                assertEquals(want == null, got == null, "row " + i + " " + column);
                if (got != null) {
                    String name = got.toString().substring(got.toString().lastIndexOf('/') + 1);
                    assertEquals(want.toString().substring(want.toString().lastIndexOf('/') + 1), name);
                    assertTrue(got.toString().startsWith("screenshots/debugger-20261027-153012/" + timeline.id() + "/"), got.toString());
                    assertTrue(Files.isRegularFile(root.resolve(got.toString())), got.toString());
                }
            }
        }
        assertEquals("clicked Save", actual.get(1).get("notes"));
        assertNull(actual.get(0).get("notes"));

        Map<String, Object> trajectory = rows(exported, "SELECT * FROM trajectories").get(0);
        assertEquals("Save a shirt", trajectory.get("task_description"));
        assertEquals("the save toast shows", trajectory.get("notes"));
        assertEquals("HUMAN", trajectory.get("execution_mode"));
        assertEquals("human", trajectory.get("agent"));
        assertEquals("debugger-20261027-153012", trajectory.get("experiment_id"));
        assertEquals(timeline.id(), trajectory.get("eval_id"));
        assertEquals("abc123", trajectory.get("odobot_commit"));
        assertEquals(timeline.id(), new JsonObject(trajectory.get("task_definition").toString()).getString("timelineId"));
        assertEquals(List.of(Map.of("value", "3")), rows(exported, "SELECT value FROM dataset_meta WHERE key = 'schema_version'"));
        assertEquals(0, rows(exported, "SELECT * FROM agent_steps").size());
        assertTrue(Files.isRegularFile(root.resolve("timelines.json")));
    }

    @Test
    void eachHalfOfACutExportsAsItsOwnTrajectoryNumberedFromZero() throws Exception {
        Workspace workspace = new Workspace();
        Path entities = dir.resolve("entities");
        Workspace.Timeline timeline = DebuggerFixtures.record(workspace, DebuggerFixtures.recording(), entities);
        Workspace.Cut cut = workspace.cut(timeline.id(), timeline.entities().get(2).id());

        Path zip = TimelineExporter.zip(exportable(workspace, entities, List.of(cut.first().id(), cut.second().id())),
                dir.resolve("work"), AT, BUILD);

        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertNotNull(file.getEntry("trajectories.db"));
            assertNotNull(file.getEntry("timelines.json"));
            assertNotNull(file.getEntry("screenshots/debugger-20261027-153012/" + cut.first().id() + "/btn_click-1.png"));
        }
        Path unpacked = dir.resolve("unpacked");
        Path db;
        try (var in = Files.newInputStream(zip)) {
            db = TimelineImporter.unzip(in, unpacked);
        }
        assertEquals(List.of(Map.of("n", 2)), rows(db, "SELECT COUNT(*) AS n FROM trajectories"));
        assertEquals(List.of(Map.of("p", 0), Map.of("p", 0)), rows(db,
                "SELECT MIN(position) AS p FROM timeline_entities GROUP BY trajectory_id"));
        assertEquals(List.of(Map.of("n", timeline.entities().size() + cut.second().entities().size())),
                rows(db, "SELECT COUNT(*) AS n FROM timeline_entities"));
    }
}
