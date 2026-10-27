package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.trajectory.StagedTrajectory;
import ca.ualberta.odobot.trajectory.TrajectoryDatabase;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports charted timelines from a trajectory database (OdoBot's dataset, a snapshot of it, or a Trajectory Debugger
 * export) into the debugger's workspace, as copies.
 *
 * <p>The source is only read: it is opened read-only, and its screenshots are copied, never moved. Each imported
 * timeline and entity gets a new id, and whatever is done to it in the debugger (renaming, notes, cuts, deletion)
 * touches only the copy. The timeline keeps where it came from in its {@code source}.</p>
 */
public final class TimelineImporter {

    private TimelineImporter() {
    }

    /**
     * @return the database's experiments and their trajectories, newest experiment first:
     * {@code {schemaVersion, experiments: [{experiment_id, trajectories: [...]}]}}.
     */
    public static JsonObject list(Path db) throws Exception {
        try (TrajectoryDatabase database = TrajectoryDatabase.openReadOnly(db)) {
            JsonArray experiments = new JsonArray();
            for (JsonObject experiment : database.experiments()) {
                experiments.add(experiment.copy().put("trajectories",
                        new JsonArray(database.trajectories(experiment.getString("experiment_id")))));
            }
            return new JsonObject().put("schemaVersion", database.schemaVersion()).put("experiments", experiments);
        }
    }

    /**
     * Import trajectories' charted timelines into the workspace, one timeline each, in the order given.
     *
     * @param db       the source database, opened read-only.
     * @param baseDir  the folder the database's screenshot paths are relative to (OdoBot's {@code db/}, or an
     *                 unpacked export's root).
     * @param label    names the source in each timeline's {@code source}, e.g. "OdoBot dataset" or a file name.
     * @param folderOf where each new entity's folder goes, by entity id.
     * @return the new timelines.
     */
    public static List<Workspace.Timeline> importInto(Workspace workspace, Path db, Path baseDir, String label,
                                                      List<String> trajectoryIds, Function<String, Path> folderOf) throws Exception {
        List<StagedTrajectory> trajectories = new ArrayList<>();
        try (TrajectoryDatabase database = TrajectoryDatabase.openReadOnly(db)) {
            for (String id : trajectoryIds) {
                StagedTrajectory trajectory = database.readTrajectory(id, "charted");
                if (trajectory == null) {
                    throw new IllegalArgumentException("The database has no trajectory " + id);
                }
                trajectories.add(trajectory);
            }
        }

        List<Workspace.Timeline> imported = new ArrayList<>();
        for (StagedTrajectory trajectory : trajectories) {
            JsonObject t = trajectory.trajectory();
            List<JsonObject> entities = new ArrayList<>();
            for (Object o : trajectory.entities()) {
                JsonObject row = (JsonObject) o;
                entities.add(new JsonObject().put("summary", EntitySummaries.of(row)).put("notes", row.getString("notes")));
            }
            String title = t.getString("task_description");
            if (title == null || title.isBlank()) {
                title = t.getString("eval_id");
            }
            Workspace.Timeline timeline = workspace.addImported(title, t.getString("notes"), source(t, label), entities);

            List<Workspace.Entity> created = timeline.entities();
            for (int i = 0; i < created.size(); i++) {
                JsonObject row = trajectory.entities().getJsonObject(i).copy();
                Path folder = folderOf.apply(created.get(i).id());
                Files.createDirectories(folder);
                for (String column : new String[]{"screenshot_path", "crop_path"}) {
                    row.put(column, copyFile(baseDir, row.getString(column), folder));
                }
                String sha = row.getString("dom_sha256");
                EntityFolder.write(folder, row, sha == null ? null : trajectory.doms().getString(sha));
            }
            imported.add(timeline);
        }
        return imported;
    }

    /**
     * @return what an imported timeline remembers of its trajectory: where it came from, and enough to tell it apart.
     */
    static JsonObject source(JsonObject trajectory, String label) {
        JsonObject source = new JsonObject().put("db", label);
        for (String column : List.of("id", "experiment_id", "eval_id", "webarena_task_id", "task_description", "execution_mode",
                "agent", "model", "eval_result", "score", "odobot_commit", "odox_version", "collected_at")) {
            source.put(column.equals("id") ? "trajectory_id" : column, trajectory.getValue(column));
        }
        return source;
    }

    /**
     * Copy a screenshot the row names into the entity's folder.
     *
     * @return the file's name in the folder, or null if the row names none or the source has no such file.
     */
    private static String copyFile(Path baseDir, String path, Path folder) throws IOException {
        if (path == null) {
            return null;
        }
        Path file = baseDir.resolve(path).normalize();
        if (!file.startsWith(baseDir.normalize()) || !Files.isRegularFile(file)) {
            return null;
        }
        String name = EntityFolder.fileName(path);
        Files.copy(file, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        return name;
    }

    /**
     * Unpack an uploaded zip (a Trajectory Debugger export, or any zip with a trajectory database and its screenshots)
     * into a folder.
     *
     * @return the trajectory database in it.
     * @throws IllegalArgumentException if it holds none.
     */
    public static Path unzip(InputStream zip, Path folder) throws IOException {
        Files.createDirectories(folder);
        Path root = folder.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("The zip holds a path outside its folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (var files = Files.walk(root, 3)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".db") && Files.isRegularFile(p)).sorted()
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("The zip holds no trajectory database (.db)."));
        }
    }
}
