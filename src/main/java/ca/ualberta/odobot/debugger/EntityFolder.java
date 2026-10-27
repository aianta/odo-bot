package ca.ualberta.odobot.debugger;

import ca.ualberta.odobot.trajectory.TrajectoryWriter;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One timeline entity of the Trajectory Debugger, as a folder: {@value #ROW} (its {@code timeline_entities} row),
 * {@value #DOM} (its page snapshot, if it has one) and its screenshot files. The row's {@code screenshot_path} and
 * {@code crop_path} are file names in the folder, and it keeps {@code dom_sha256}. Live recordings, imports and exports
 * all go through this format, so a row reads the same wherever it came from.
 */
public final class EntityFolder {

    public static final String ROW = "row.json";
    public static final String DOM = "dom.html";

    private EntityFolder() {
    }

    /**
     * One entity as read from its folder.
     *
     * @param row     the row, its file columns naming files in {@code folder}.
     * @param domHtml its page snapshot, or null.
     */
    public record Entity(Path folder, JsonObject row, String domHtml) {

        /**
         * @return the file the row's column names, or null if it names none or the file is missing.
         */
        public Path file(String column) {
            String name = row.getString(column);
            if (name == null) {
                return null;
            }
            Path file = folder.resolve(name);
            return Files.isRegularFile(file) ? file : null;
        }
    }

    /**
     * Write a row built by {@link TrajectoryWriter#chartedRow} into a folder. Its screenshot files must already have
     * been written there; the row's file columns are cut down to file names.
     */
    public static void write(Path folder, TrajectoryWriter.EntityRow entityRow) throws IOException {
        JsonObject row = entityRow.row().copy();
        for (String column : new String[]{"screenshot_path", "crop_path"}) {
            String path = row.getString(column);
            if (path != null) {
                row.put(column, fileName(path));
            }
        }
        write(folder, row, entityRow.domHtml());
    }

    /**
     * Write a row and its page snapshot into a folder, replacing what it held.
     */
    public static void write(Path folder, JsonObject row, String domHtml) throws IOException {
        Files.createDirectories(folder);
        Path temp = folder.resolve(ROW + ".tmp");
        Files.writeString(temp, row.encode(), StandardCharsets.UTF_8);
        Files.move(temp, folder.resolve(ROW), StandardCopyOption.REPLACE_EXISTING);
        if (domHtml != null) {
            Files.writeString(folder.resolve(DOM), domHtml, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(folder.resolve(DOM));
        }
    }

    /**
     * @return the entity in the folder.
     * @throws IOException if the folder holds no row.
     */
    public static Entity read(Path folder) throws IOException {
        JsonObject row = new JsonObject(Files.readString(folder.resolve(ROW), StandardCharsets.UTF_8));
        Path dom = folder.resolve(DOM);
        return new Entity(folder, row, Files.isRegularFile(dom) ? Files.readString(dom, StandardCharsets.UTF_8) : null);
    }

    public static boolean exists(Path folder) {
        return Files.isRegularFile(folder.resolve(ROW));
    }

    /**
     * Copy an entity's folder: its row, page snapshot and screenshot files.
     */
    public static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, to.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Move an entity's folder, or copy it if it can't be moved, e.g. across file systems.
     */
    public static void move(Path from, Path to) throws IOException {
        if (to.getParent() != null) {
            Files.createDirectories(to.getParent());
        }
        delete(to);
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            copy(from, to);
            delete(from);
        }
    }

    /**
     * Delete a folder and everything in it, if it exists.
     */
    public static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash == -1 ? path : path.substring(slash + 1);
    }
}
