package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.trajectory.StagedTrajectory;
import ca.ualberta.odobot.trajectory.TrajectoryDatabase;
import ca.ualberta.odobot.trajectory.TrajectoryStore;
import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports Trajectory Debugger timelines as a zip laid out like OdoBot's {@code db/} folder:
 *
 * <pre>
 * debugger-&lt;yyyyMMdd-HHmmss&gt;.zip
 * ├── trajectories.db                       the trajectory schema, written by {@link TrajectoryDatabase}
 * ├── screenshots/&lt;experimentId&gt;/&lt;timelineId&gt;/   each timeline's screenshots and crops
 * └── timelines.json                        each timeline as staged: the same rows, files and page snapshots
 * </pre>
 *
 * <p>Each timeline is one {@code trajectories} row ({@code execution_mode = HUMAN}, its title as
 * {@code task_description}, its notes as {@code notes}) with its entities as charted {@code timeline_entities} rows,
 * positions numbered from 0, each with its notes. Everything else in an entity row is as OdoBot saves it for a task
 * (written by {@link TrajectoryWriter#chartedRow}, or as read from the database it was imported from), and its page
 * snapshot goes to {@code dom_snapshots}. Screenshot files are named as OdoBot names them, after the entity's event and
 * position. Human recordings have no model steps, so {@code agent_steps} and {@code step_entity_links} are empty.</p>
 */
public final class TimelineExporter {

    public static final String EXECUTION_MODE = "HUMAN";
    public static final String AGENT = "human";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private TimelineExporter() {
    }

    /**
     * A timeline to export, with its entities' rows in order.
     *
     * @param rows the folder of each of the timeline's entities, in the timeline's order.
     */
    public record Timeline(Workspace.Timeline timeline, List<EntityFolder.Entity> rows) {
    }

    /**
     * What the export records about the OdoBot that made it.
     */
    public record Build(String odobotCommit, String odoxVersion) {
    }

    /**
     * @return the experiment id of an export made at the given time, {@code debugger-<yyyyMMdd-HHmmss>}.
     */
    public static String experimentId(Instant at) {
        return "debugger-" + STAMP.format(at);
    }

    /**
     * Export the timelines into {@code root} (which must be empty or not exist): the database, the screenshots and
     * {@code timelines.json}.
     *
     * @return the staged trajectories written, one per timeline.
     */
    public static List<StagedTrajectory> write(List<Timeline> timelines, Path root, Instant at, Build build) throws Exception {
        Files.createDirectories(root);
        String experimentId = experimentId(at);
        List<StagedTrajectory> staged = new ArrayList<>();
        for (Timeline timeline : timelines) {
            staged.add(stage(timeline, experimentId, root, at, build));
        }
        try (TrajectoryDatabase database = TrajectoryDatabase.open(root.resolve("trajectories.db"))) {
            try {
                for (StagedTrajectory trajectory : staged) {
                    database.insert(trajectory);
                }
                database.putMeta("exported_by", "trajectory-debugger");
                database.putMeta("exported_at", at.toString());
                database.commit();
            } catch (Exception e) {
                database.rollback();
                throw e;
            }
        }
        Files.writeString(root.resolve("timelines.json"),
                new JsonArray(staged.stream().map(StagedTrajectory::toJson).toList()).encodePrettily(), StandardCharsets.UTF_8);
        return staged;
    }

    /**
     * Export the timelines as a zip.
     *
     * @param workDir a folder for the export's files, deleted afterwards.
     * @return the zip, {@code <experimentId>.zip} in the parent of {@code workDir}.
     */
    public static Path zip(List<Timeline> timelines, Path workDir, Instant at, Build build) throws Exception {
        Path root = workDir.resolve(experimentId(at));
        EntityFolder.delete(root);
        write(timelines, root, at, build);
        Path zip = workDir.resolve(experimentId(at) + ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                zipOut.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zipOut);
                zipOut.closeEntry();
            }
        }
        EntityFolder.delete(root);
        return zip;
    }

    static StagedTrajectory stage(Timeline export, String experimentId, Path root, Instant at, Build build) throws IOException {
        Workspace.Timeline timeline = export.timeline();
        String fileStem = TrajectoryStore.safeName(timeline.id());
        Path screenshots = root.resolve("screenshots").resolve(TrajectoryStore.safeName(experimentId)).resolve(fileStem);

        JsonArray entities = new JsonArray();
        JsonObject doms = new JsonObject();
        List<String> files = new ArrayList<>();
        List<Workspace.Entity> workspaceEntities = timeline.entities();
        Long first = null, last = null;
        String finalUrl = null;
        for (int position = 0; position < export.rows().size(); position++) {
            EntityFolder.Entity source = export.rows().get(position);
            JsonObject row = source.row().copy();
            row.put("timeline", "charted").put("position", position)
                    .put("notes", position < workspaceEntities.size() ? workspaceEntities.get(position).notes() : null);

            String name = TrajectoryWriter.screenshotName(row, position);
            row.put("screenshot_path", copyFile(source.file("screenshot_path"), screenshots, name + ".png", experimentId, fileStem, files));
            row.put("crop_path", copyFile(source.file("crop_path"), screenshots, name + "-crop.png", experimentId, fileStem, files));

            String sha = row.getString("dom_sha256");
            if (sha != null && source.domHtml() != null) {
                doms.put(sha, source.domHtml());
            } else {
                row.remove("dom_sha256");
            }

            Long timestamp = row.getValue("timestamp") instanceof Number n ? n.longValue() : null;
            if (timestamp != null) {
                first = first == null ? timestamp : Math.min(first, timestamp);
                last = last == null ? timestamp : Math.max(last, timestamp);
            }
            if (row.getString("location") != null) {
                finalUrl = row.getString("location");
            }
            entities.add(row);
        }

        JsonObject source = timeline.source();
        JsonObject trajectory = new JsonObject()
                .put("id", UUID.randomUUID().toString())
                .put("eval_id", timeline.id())
                .put("experiment_id", experimentId)
                .put("task_id", null)
                .put("webarena_task_id", null)
                .put("task_description", timeline.title())
                .put("task_definition", new JsonObject()
                        .put("title", timeline.title())
                        .put("timelineId", timeline.id())
                        .put("createdAt", timeline.createdAt())
                        .put("exportedAt", at.toString())
                        .put("source", source))
                .put("execution_mode", EXECUTION_MODE)
                .put("agent", AGENT)
                .put("model", null)
                .put("agent_config", null)
                .put("reasoning_required", false)
                .put("odobot_commit", build.odobotCommit())
                .put("odox_version", source != null && source.getString("odox_version") != null ? source.getString("odox_version") : build.odoxVersion())
                .put("collected_at", first != null ? first : timeline.createdAt())
                .put("ended_at", last)
                .put("end_reason", "exported")
                .put("final_url", finalUrl)
                .put("notes", timeline.notes());
        return new StagedTrajectory(experimentId, timeline.id(), fileStem, trajectory, entities, doms, new JsonArray(),
                new JsonArray(), files);
    }

    /**
     * Copy a screenshot into the export.
     *
     * @return its path relative to the export's root, as rows name files relative to {@code db/}; null if there is no file.
     */
    private static String copyFile(Path file, Path folder, String name, String experimentId, String fileStem, List<String> files) throws IOException {
        if (file == null) {
            return null;
        }
        Files.createDirectories(folder);
        Files.copy(file, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        files.add(name);
        return TrajectoryStore.committedPath(experimentId, fileStem, name);
    }
}
