package ca.ualberta.odobot.trajectory;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The only writer of {@code db/trajectories.db}: every task's trajectory goes through here, one transaction at a time.
 *
 * <p>A trajectory reaches the database in two moves. The task stages it, a durable copy in
 * {@code db/staging/<experimentId>/<fileStem>/} (see {@link StagedTrajectory}), and only then lets go of its timelines.
 * The store then commits the staged copy on its single writer thread, moves its screenshots to
 * {@code db/screenshots/<experimentId>/<fileStem>/} and deletes the staging folder. A failed commit leaves the folder
 * staged; it is retried, and committed again at the next start, so nothing staged is lost. Committing a folder twice
 * replaces the trajectory, so retries are safe.</p>
 *
 * <p>The database file sits on a bind mount from a Windows host, so it uses the rollback journal (not WAL) and one
 * connection. Read it from the host through a {@link #snapshot()}, not while a run writes it.</p>
 */
public class TrajectoryStore {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryStore.class);

    /**
     * The version of {@link TrajectorySchema}. A database of version 2 is migrated (version 3 adds {@code notes}), one
     * of any other version is refused, see {@link TrajectoryDatabase#open}.
     */
    public static final int SCHEMA_VERSION = 3;

    public static final String STAGED_FILE = "trajectory.json";

    /**
     * How long another writer's heartbeat stays fresh, in milliseconds.
     */
    static final long HEARTBEAT_STALE_MS = 90_000;

    static final long HEARTBEAT_PERIOD_MS = 30_000;

    /**
     * How long to wait before each retry of a failed commit, in milliseconds.
     */
    static long[] commitRetryDelaysMs = {5_000, 30_000, 120_000};

    private static TrajectoryStore instance;

    private final Vertx vertx;
    private final Path dbDir;
    private final Path dbFile;
    private final Path stagingDir;
    private final Path screenshotsDir;
    private final WorkerExecutor writer;
    private final String writerId;

    /**
     * Only used on the writer thread.
     */
    private TrajectoryDatabase database;

    /**
     * The open database's connection, see {@link #connect()}. Only used on the writer thread.
     */
    private Connection connection;

    private volatile boolean ready = false;

    /**
     * Why this store may not write, or null if it may.
     */
    private volatile String writerProblem = "starting";

    private volatile String lastError = null;
    private volatile long committed = 0;
    private volatile long heartbeatTimer = -1;

    TrajectoryStore(Vertx vertx, Path dbDir) {
        this.vertx = vertx;
        this.dbDir = dbDir;
        this.dbFile = dbDir.resolve("trajectories.db");
        this.stagingDir = dbDir.resolve("staging");
        this.screenshotsDir = dbDir.resolve("screenshots");
        this.writer = vertx.createSharedWorkerExecutor("trajectory-writer-" + dbDir.toAbsolutePath().normalize(), 1);
        this.writerId = writerId();
    }

    /**
     * Open the store in {@code dbDir}: claim the database, then commit whatever an earlier run left staged.
     *
     * @return completes once the store can write, or fails if another OdoBot is writing the database.
     */
    public static synchronized Future<TrajectoryStore> init(Vertx vertx, Path dbDir) {
        if (instance != null) {
            return Future.succeededFuture(instance);
        }
        TrajectoryStore store = new TrajectoryStore(vertx, dbDir);
        instance = store;
        return store.start().map(v -> store);
    }

    /**
     * @return the store, or null if {@link #init} has not been called.
     */
    public static synchronized TrajectoryStore get() {
        return instance;
    }

    /**
     * Forget the store, for tests. It is closed first.
     */
    static synchronized void reset() {
        if (instance != null) {
            instance.close();
        }
        instance = null;
    }

    Future<Void> start() {
        return writer.<Void>executeBlocking(() -> {
            Files.createDirectories(stagingDir);
            Files.createDirectories(screenshotsDir);
            connect();
            claimWriter();
            return null;
        }, true).compose(v -> {
            ready = true;
            heartbeatTimer = vertx.setPeriodic(HEARTBEAT_PERIOD_MS, id -> writer.executeBlocking(() -> {
                heartbeat();
                return null;
            }, true).onFailure(err -> log.warn("Trajectory store heartbeat failed: {}", err.getMessage())));
            return recover().mapEmpty();
        }, err -> {
            writerProblem = err.getMessage();
            log.error("Trajectories can't be saved: {}", err.getMessage());
            return Future.failedFuture(err);
        });
    }

    /**
     * @return true once the store holds the database and may write it.
     */
    public boolean canWrite() {
        return ready && writerProblem == null;
    }

    /**
     * @return where a task stages its trajectory.
     */
    public Path stagingFolder(String experimentId, String fileStem) {
        return stagingDir.resolve(safeName(experimentId)).resolve(safeName(fileStem));
    }

    /**
     * @return the path, relative to {@code db/}, a staged screenshot has once committed.
     */
    public static String committedPath(String experimentId, String fileStem, String fileName) {
        return "screenshots/" + safeName(experimentId) + "/" + safeName(fileStem) + "/" + fileName;
    }

    public Path dbDir() {
        return dbDir;
    }

    /**
     * Commit a staged trajectory, retrying with backoff if it fails. A trajectory that still can't be committed stays
     * staged for the next start, or {@link #importStaged()}.
     *
     * @return completes once the trajectory is committed, or fails once the retries are used up.
     */
    public Future<Void> commit(Path stagedFolder) {
        return commit(stagedFolder, 0);
    }

    private Future<Void> commit(Path stagedFolder, int retry) {
        return writer.<Void>executeBlocking(() -> {
            commitNow(stagedFolder);
            return null;
        }, true).recover(err -> {
            lastError = "%s: %s".formatted(stagedFolder.getFileName(), err.getMessage());
            if (retry >= commitRetryDelaysMs.length) {
                log.error("Could not commit the trajectory staged in {}, it stays staged: {}", stagedFolder, err.getMessage(), err);
                return Future.failedFuture(err);
            }
            log.warn("Could not commit the trajectory staged in {}, retrying in {} ms: {}", stagedFolder,
                    commitRetryDelaysMs[retry], err.getMessage());
            Promise<Void> retried = Promise.promise();
            vertx.setTimer(Math.max(1, commitRetryDelaysMs[retry]), id -> commit(stagedFolder, retry + 1).onComplete(retried));
            return retried.future();
        });
    }

    /**
     * Commit every complete staged trajectory, e.g. those an earlier run left when it stopped.
     *
     * @return how many were committed.
     */
    public Future<Integer> importStaged() {
        return recover();
    }

    private Future<Integer> recover() {
        List<Path> staged;
        try {
            staged = stagedFolders();
        } catch (IOException e) {
            return Future.failedFuture(e);
        }
        if (!staged.isEmpty()) {
            log.info("Committing {} staged trajectories.", staged.size());
        }
        Future<Integer> chain = Future.succeededFuture(0);
        for (Path folder : staged) {
            chain = chain.compose(count -> writer.<Void>executeBlocking(() -> {
                commitNow(folder);
                return null;
            }, true).map(v -> count + 1).recover(err -> {
                lastError = "%s: %s".formatted(folder.getFileName(), err.getMessage());
                log.error("Could not commit the trajectory staged in {}, it stays staged: {}", folder, err.getMessage());
                return Future.succeededFuture(count);
            }));
        }
        return chain;
    }

    /**
     * @return the staging folders that hold a complete staged trajectory.
     */
    List<Path> stagedFolders() throws IOException {
        if (!Files.isDirectory(stagingDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(stagingDir, 3)) {
            return files.filter(path -> path.getFileName().toString().equals(STAGED_FILE)).map(Path::getParent).sorted().toList();
        }
    }

    /**
     * @return the store's state: whether it may write, how many trajectories are staged but not committed, how many it
     * committed since it started, and the last commit error.
     */
    public JsonObject status() {
        return status(null);
    }

    /**
     * @param experimentId if not null, also list the file stems of that experiment's trajectories that are staged but
     *                     not committed, as {@code stagedEvalIds}.
     */
    public JsonObject status(String experimentId) {
        List<Path> folders;
        try {
            folders = stagedFolders();
        } catch (IOException e) {
            folders = null;
        }
        JsonObject status = new JsonObject()
                .put("database", dbFile.toString())
                .put("canWrite", canWrite())
                .put("writerProblem", writerProblem)
                .put("writer", writerId)
                .put("staged", folders == null ? -1 : folders.size())
                .put("committed", committed)
                .put("lastError", lastError);
        if (experimentId != null && folders != null) {
            Path experiment = stagingDir.resolve(safeName(experimentId));
            status.put("experimentId", experimentId)
                    .put("stagedEvalIds", new JsonArray(folders.stream().filter(f -> experiment.equals(f.getParent()))
                            .map(f -> f.getFileName().toString()).toList()));
        }
        return status;
    }

    /**
     * Copy the database, consistently, to {@code db/trajectories-snapshot-<time>.db}, for reading while a run writes.
     *
     * @return the snapshot's path.
     */
    public Future<String> snapshot() {
        return writer.executeBlocking(() -> {
            connect();
            Path target = dbDir.resolve("trajectories-snapshot-%s.db".formatted(Instant.now().toString().replace(':', '-')));
            //VACUUM can't run inside a transaction.
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
            } finally {
                connection.setAutoCommit(false);
            }
            log.info("Trajectory database snapshot written to {}", target);
            return target.toString();
        }, true);
    }

    /**
     * Drop page snapshots no trajectory uses any more, then compact the database. It needs about twice the database's
     * size free, and blocks commits while it runs, so run it between runs.
     */
    public Future<Void> vacuum() {
        return writer.<Void>executeBlocking(() -> {
            connect();
            try (Statement statement = connection.createStatement()) {
                int dropped = statement.executeUpdate("DELETE FROM dom_snapshots WHERE id NOT IN "
                        + "(SELECT dom_snapshot_id FROM timeline_entities WHERE dom_snapshot_id IS NOT NULL)");
                connection.commit();
                connection.setAutoCommit(true);
                try {
                    statement.execute("VACUUM");
                } finally {
                    connection.setAutoCommit(false);
                }
                log.info("Trajectory database vacuumed, {} unused page snapshots dropped.", dropped);
            }
            return null;
        }, true);
    }

    /**
     * Release the database and close its connection on the writer thread.
     *
     * @return completes once the connection is closed, so the file can be moved or deleted.
     */
    public synchronized Future<Void> close() {
        if (heartbeatTimer != -1) {
            vertx.cancelTimer(heartbeatTimer);
            heartbeatTimer = -1;
        }
        ready = false;
        return writer.<Void>executeBlocking(() -> {
            releaseWriter();
            if (database != null) {
                database.close();
                database = null;
                connection = null;
            }
            return null;
        }, true).eventually(() -> writer.close());
    }

    // ---------------------------------------------------------------- writer thread

    private void connect() throws SQLException {
        if (database != null && !database.isClosed()) {
            return;
        }
        database = TrajectoryDatabase.open(dbFile);
        connection = database.connection();
    }

    /**
     * Take the database unless another OdoBot holds it: one whose heartbeat is fresh and that runs elsewhere. An earlier
     * run in the same container is gone, since a container runs one OdoBot.
     */
    private void claimWriter() throws SQLException {
        JsonObject current = null;
        try (PreparedStatement select = connection.prepareStatement("SELECT value FROM dataset_meta WHERE key = 'writer'");
             ResultSet row = select.executeQuery()) {
            if (row.next()) {
                current = new JsonObject(row.getString(1));
            }
        }
        if (current != null) {
            boolean fresh = System.currentTimeMillis() - current.getLong("heartbeatAt", 0L) < HEARTBEAT_STALE_MS;
            boolean elsewhere = !hostOf(writerId).equals(hostOf(current.getString("id", "")));
            if (fresh && elsewhere) {
                throw new IllegalStateException("another OdoBot (%s) is writing %s, its heartbeat is %d s old".formatted(
                        current.getString("id"), dbFile, (System.currentTimeMillis() - current.getLong("heartbeatAt")) / 1000));
            }
        }
        heartbeat();
        writerProblem = null;
    }

    private void heartbeat() throws SQLException {
        connect();
        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO dataset_meta(key, value) VALUES ('writer', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            upsert.setString(1, new JsonObject().put("id", writerId).put("heartbeatAt", System.currentTimeMillis()).encode());
            upsert.executeUpdate();
        }
        connection.commit();
    }

    private void releaseWriter() {
        try {
            if (connection == null || connection.isClosed() || writerProblem != null) {
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM dataset_meta WHERE key = 'writer' AND value LIKE ?")) {
                delete.setString(1, "%\"" + writerId.replace("%", "") + "\"%");
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            log.warn("Could not release the trajectory database: {}", e.getMessage());
        }
    }

    /**
     * Commit one staged trajectory, replacing any earlier one of the same experiment and evalId, then move its
     * screenshots into place and delete the staging folder.
     */
    void commitNow(Path folder) throws Exception {
        if (writerProblem != null) {
            throw new IllegalStateException("trajectories can't be saved: " + writerProblem);
        }
        Path stagedFile = folder.resolve(STAGED_FILE);
        if (!Files.isRegularFile(stagedFile)) {
            //Already committed, e.g. by an earlier retry.
            return;
        }
        StagedTrajectory staged = StagedTrajectory.fromJson(new JsonObject(Files.readString(stagedFile, StandardCharsets.UTF_8)));
        connect();
        try {
            insert(staged);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        }
        moveScreenshots(folder, staged);
        Files.delete(stagedFile);
        deleteFolder(folder);
        committed++;
        log.info("Trajectory of {} ({}) committed.", staged.evalId(), staged.experimentId());
    }

    private void insert(StagedTrajectory staged) throws SQLException {
        database.insert(staged);
    }

    /**
     * Move the staged screenshots to their committed folder. Files the folder holds from an earlier run of the task, and
     * that this trajectory does not have, are deleted. A move that was cut short is finished by committing again.
     */
    private void moveScreenshots(Path folder, StagedTrajectory staged) throws IOException {
        Path target = screenshotsDir.resolve(safeName(staged.experimentId())).resolve(safeName(staged.fileStem()));
        Files.createDirectories(target);
        Set<String> files = new HashSet<>(staged.files());
        try (Stream<Path> existing = Files.list(target)) {
            for (Path old : existing.toList()) {
                if (!files.contains(old.getFileName().toString())) {
                    Files.deleteIfExists(old);
                }
            }
        }
        for (String name : files) {
            Path source = folder.resolve(name);
            if (!Files.exists(source)) {
                continue; //Moved by an earlier attempt.
            }
            try {
                Files.move(source, target.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void deleteFolder(Path folder) throws IOException {
        try (Stream<Path> leftovers = Files.list(folder)) {
            for (Path leftover : leftovers.toList()) {
                Files.deleteIfExists(leftover);
            }
        }
        Files.deleteIfExists(folder);
        Path experiment = folder.getParent();
        if (experiment != null && !experiment.equals(stagingDir)) {
            try (Stream<Path> rest = Files.list(experiment)) {
                if (rest.findAny().isEmpty()) {
                    Files.deleteIfExists(experiment);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @return the name with every character a file name can't safely hold replaced by '-'.
     */
    public static String safeName(String name) {
        return name == null || name.isBlank() ? "unknown" : name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String writerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "unknown-host";
        }
        return host + "/" + ManagementFactory.getRuntimeMXBean().getPid() + "/" + Instant.now().toEpochMilli();
    }

    private static String hostOf(String id) {
        int slash = id.indexOf('/');
        return slash == -1 ? id : id.substring(0, slash);
    }

    /**
     * For tests: the columns a table has, in order.
     */
    List<String> columns(String table) throws Exception {
        return writer.<List<String>>executeBlocking(() -> {
            connect();
            List<String> columns = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA table_xinfo(" + table + ")")) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                }
            }
            return columns;
        }, true).toCompletionStage().toCompletableFuture().get();
    }
}
