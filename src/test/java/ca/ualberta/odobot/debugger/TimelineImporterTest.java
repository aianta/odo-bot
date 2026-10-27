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
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static ca.ualberta.odobot.debugger.TimelineExporterTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TimelineImporterTest {

    @TempDir
    Path dir;

    /**
     * An experiment database like OdoBot's db/: two task trajectories, their screenshots committed under it.
     */
    private Path dataset() throws Exception {
        Path base = dir.resolve("db");
        try (TrajectoryDatabase database = TrajectoryDatabase.open(Files.createDirectories(base).resolve("trajectories.db"))) {
            for (String evalId : List.of("1|OdoBotNL|a", "2|OdoBotNL|b")) {
                OnlineEventProcessor processor = DebuggerFixtures.recording();
                Path staging = dir.resolve("staging-" + evalId.charAt(0));
                var staged = TrajectoryWriter.stage(TrajectoryWriterTest.run(evalId), processor, null, staging);
                database.insert(staged);
                //Commit the screenshots where the rows say they are, as the store does.
                Path target = base.resolve("screenshots/exp-1/" + staged.fileStem());
                Files.createDirectories(target);
                for (String file : staged.files()) {
                    Files.copy(staging.resolve(file), target.resolve(file));
                }
            }
            database.commit();
        }
        return base;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /**
     * @return every file under the folder with its hash.
     */
    private static Map<String, String> fingerprint(Path folder) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(path).toString(), sha256(path));
            }
        }
        return files;
    }

    private static List<String> ids(Path db) throws Exception {
        return TimelineImporter.list(db).getJsonArray("experiments").getJsonObject(0).getJsonArray("trajectories")
                .stream().map(o -> ((JsonObject) o).getString("id")).toList();
    }

    @Test
    void importedTimelinesAreCopiesAndNothingDoneToThemTouchesTheSource() throws Exception {
        Path base = dataset();
        Path db = base.resolve("trajectories.db");
        Map<String, String> before = fingerprint(base);

        Workspace workspace = new Workspace();
        Path entities = dir.resolve("workspace/entities");
        List<String> ids = ids(db);
        assertEquals(2, ids.size());
        List<Workspace.Timeline> imported = TimelineImporter.importInto(workspace, db, base, "OdoBot dataset",
                List.of(ids.get(0), ids.get(1), ids.get(0)), entities::resolve);

        assertEquals(3, imported.size(), "several at once, and the same one twice");
        assertNotEquals(imported.get(0).id(), imported.get(2).id(), "two independent copies");
        assertNotEquals(imported.get(0).entities().get(0).id(), imported.get(2).entities().get(0).id());
        assertEquals("1|OdoBotNL|a", imported.get(0).source().getString("eval_id"));
        assertEquals("OdoBot dataset", imported.get(0).source().getString("db"));
        assertNull(workspace.activeTimelineId(), "importing doesn't change the active timeline");

        Workspace.Entity click = imported.get(0).entities().get(1);
        EntityFolder.Entity clickFolder = EntityFolder.read(entities.resolve(click.id()));
        assertNotNull(clickFolder.file("screenshot_path"), "screenshots are copied in");
        assertNotNull(clickFolder.file("crop_path"));
        assertNotNull(clickFolder.domHtml());

        //Rename, annotate, cut and delete the copies.
        workspace.rename(imported.get(0).id(), "renamed");
        workspace.setNotes(imported.get(0).id(), "notes");
        workspace.setEntityNotes(click.id(), "entity notes");
        workspace.cut(imported.get(0).id(), click.id());
        for (Workspace.Entity entity : workspace.delete(imported.get(1).id())) {
            EntityFolder.delete(entities.resolve(entity.id()));
        }

        assertEquals(before, fingerprint(base), "the source database and its screenshots are unchanged");
        assertEquals(2, ids(db).size());
    }

    @Test
    void anImportedTimelineExportsTheSameRowsAndNotesItWasExportedWith() throws Exception {
        Workspace workspace = new Workspace();
        Path entities = dir.resolve("entities");
        Workspace.Timeline timeline = DebuggerFixtures.record(workspace, DebuggerFixtures.recording(), entities);
        workspace.setNotes(timeline.id(), "timeline notes");
        workspace.setEntityNotes(timeline.entities().get(2).id(), "typed Shirt");
        Path first = dir.resolve("first");
        TimelineExporter.write(exportable(workspace, entities, List.of(timeline.id())), first, AT, BUILD);

        Workspace other = new Workspace();
        Path otherEntities = dir.resolve("other");
        Path firstDb = first.resolve("trajectories.db");
        List<Workspace.Timeline> imported = TimelineImporter.importInto(other, firstDb, first, "first.zip", ids(firstDb),
                otherEntities::resolve);
        assertEquals("timeline notes", imported.get(0).notes());
        assertEquals("typed Shirt", imported.get(0).entities().get(2).notes());

        Path second = dir.resolve("second");
        TimelineExporter.write(exportable(other, otherEntities, List.of(imported.get(0).id())), second, AT, BUILD);

        String sql = """
                SELECT e.*, d.html FROM timeline_entities e LEFT JOIN dom_snapshots d ON d.id = e.dom_snapshot_id ORDER BY e.position""";
        List<Map<String, Object>> a = rows(firstDb, sql), b = rows(second.resolve("trajectories.db"), sql);
        assertEquals(a.size(), b.size());
        Set<String> different = Set.of("id", "trajectory_id", "dom_snapshot_id", "screenshot_path", "crop_path");
        for (int i = 0; i < a.size(); i++) {
            for (String column : a.get(i).keySet()) {
                if (!different.contains(column)) {
                    assertEquals(a.get(i).get(column), b.get(i).get(column), "row " + i + " column " + column);
                }
            }
            assertEquals(a.get(i).get("screenshot_path") == null, b.get(i).get("screenshot_path") == null);
        }
        assertEquals("timeline notes", rows(second.resolve("trajectories.db"), "SELECT notes FROM trajectories").get(0).get("notes"));
    }

    @Test
    void aVersion2DatabaseImportsWithoutNotesAndIsNotMigrated() throws Exception {
        Path base = dataset();
        Path db = base.resolve("trajectories.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE trajectories DROP COLUMN notes");
            statement.execute("ALTER TABLE timeline_entities DROP COLUMN notes");
            statement.executeUpdate("UPDATE dataset_meta SET value = '2' WHERE key = 'schema_version'");
        }
        String hash = sha256(db);

        Workspace workspace = new Workspace();
        List<Workspace.Timeline> imported = TimelineImporter.importInto(workspace, db, base, "old", List.of(ids(db).get(0)),
                dir.resolve("entities")::resolve);
        assertNull(imported.get(0).notes());
        assertTrue(imported.get(0).entities().stream().allMatch(e -> e.notes() == null));
        assertEquals("2", TimelineImporter.list(db).getString("schemaVersion"));
        assertEquals(hash, sha256(db), "a read-only import never migrates the source");
    }
}
