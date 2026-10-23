package ca.ualberta.odobot.trajectory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * A trajectory as staged on disk, {@value TrajectoryStore#STAGED_FILE} in its staging folder next to its screenshots:
 * every row it goes into the database as, keyed so that the store can link them once they have ids.
 *
 * <p>Entities are keyed {@code <timeline>:<position>}; steps link to their observations, and links to their entity,
 * through those keys, and links to their step by step number.</p>
 *
 * @param trajectory the {@code trajectories} row.
 * @param entities   {@code timeline_entities} rows, each with {@code dom_sha256} in place of {@code dom_snapshot_id}.
 * @param doms       page snapshots by sha256.
 * @param steps      {@code agent_steps} rows, each with {@code input_observation_key} and {@code result_observation_key}.
 * @param links      {@code step_entity_links} rows, each with {@code step_number} and {@code entity_key}.
 * @param files      the screenshot files in the staging folder.
 */
public record StagedTrajectory(String experimentId, String evalId, String fileStem, JsonObject trajectory,
                               JsonArray entities, JsonObject doms, JsonArray steps, JsonArray links, List<String> files) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("schemaVersion", TrajectoryStore.SCHEMA_VERSION)
                .put("experimentId", experimentId)
                .put("evalId", evalId)
                .put("fileStem", fileStem)
                .put("trajectory", trajectory)
                .put("entities", entities)
                .put("doms", doms)
                .put("steps", steps)
                .put("links", links)
                .put("files", new JsonArray(files));
    }

    public static StagedTrajectory fromJson(JsonObject json) {
        return new StagedTrajectory(
                json.getString("experimentId"),
                json.getString("evalId"),
                json.getString("fileStem"),
                json.getJsonObject("trajectory"),
                json.getJsonArray("entities", new JsonArray()),
                json.getJsonObject("doms", new JsonObject()),
                json.getJsonArray("steps", new JsonArray()),
                json.getJsonArray("links", new JsonArray()),
                json.getJsonArray("files", new JsonArray()).stream().map(String::valueOf).toList());
    }

    /**
     * Write {@value TrajectoryStore#STAGED_FILE} into the folder durably: to a temporary file, forced to disk, then
     * renamed into place, so the folder never holds a partial one. Its screenshots must already be there.
     */
    public void write(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path temp = folder.resolve(TrajectoryStore.STAGED_FILE + ".tmp");
        byte[] bytes = toJson().encode().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Path target = folder.resolve(TrajectoryStore.STAGED_FILE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
