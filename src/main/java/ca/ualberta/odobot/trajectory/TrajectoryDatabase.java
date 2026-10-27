package ca.ualberta.odobot.trajectory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One connection to a trajectory database, {@code db/trajectories.db} or any file of its schema (a snapshot, a
 * Trajectory Debugger export): its schema, and inserting and reading trajectories. {@link TrajectoryStore} writes the
 * experiment database through it, so every file of this schema is written by the same code.
 *
 * <p>Not thread safe: use one from one thread at a time. A writable database is left in a transaction; the caller
 * commits or rolls back.</p>
 */
public final class TrajectoryDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryDatabase.class);

    private final Path file;
    private final boolean readOnly;
    private final Connection connection;

    /**
     * The columns rows are inserted with, by table, see {@link #insertColumns}.
     */
    private final Map<String, List<String>> insertColumns = new HashMap<>();

    private TrajectoryDatabase(Path file, boolean readOnly, Connection connection) {
        this.file = file;
        this.readOnly = readOnly;
        this.connection = connection;
    }

    /**
     * Open the database for writing, creating its schema if it is new and migrating it if it is of an older version.
     *
     * @throws SQLException if it is of a version this OdoBot can't migrate.
     */
    public static TrajectoryDatabase open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = DELETE");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 30000");
                statement.execute("PRAGMA synchronous = FULL");
            }
            connection.setAutoCommit(false);
            TrajectoryDatabase database = new TrajectoryDatabase(file, false, connection);
            database.createSchema();
            return database;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /**
     * Open the database read-only, of any schema version, without creating or migrating anything.
     */
    public static TrajectoryDatabase openReadOnly(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(30000);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file, config.toProperties());
        return new TrajectoryDatabase(file, true, connection);
    }

    public Connection connection() {
        return connection;
    }

    public Path file() {
        return file;
    }

    public boolean isClosed() throws SQLException {
        return connection.isClosed();
    }

    public void commit() throws SQLException {
        connection.commit();
    }

    public void rollback() throws SQLException {
        connection.rollback();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // ---------------------------------------------------------------- schema

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : TrajectorySchema.STATEMENTS) {
                statement.execute(ddl);
            }
            String found = schemaVersion();
            if (found == null) {
                statement.execute("INSERT INTO dataset_meta(key, value) VALUES ('schema_version', '" + TrajectoryStore.SCHEMA_VERSION + "')");
                statement.execute("INSERT OR IGNORE INTO dataset_meta(key, value) VALUES ('created_at', '" + Instant.now() + "')");
            } else if (found.equals("2") && TrajectoryStore.SCHEMA_VERSION == 3) {
                migrateFrom2();
            } else if (!String.valueOf(TrajectoryStore.SCHEMA_VERSION).equals(found)) {
                connection.rollback();
                throw new SQLException("%s has schema version %s, this OdoBot writes version %d: migrate it or move it aside"
                        .formatted(file, found, TrajectoryStore.SCHEMA_VERSION));
            }
        }
        connection.commit();
    }

    /**
     * Version 3 adds {@code notes} to {@code trajectories} and {@code timeline_entities}, for the Trajectory Debugger.
     * Rows already there keep NULL. Runs in the schema's transaction.
     */
    private void migrateFrom2() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : List.of("trajectories", "timeline_entities")) {
                if (!tableColumns(table).contains("notes")) {
                    statement.execute("ALTER TABLE " + table + " ADD COLUMN notes TEXT");
                }
            }
            statement.execute("UPDATE dataset_meta SET value = '3' WHERE key = 'schema_version'");
        }
        log.info("Migrated {} from schema version 2 to 3.", file);
    }

    /**
     * @return the database's schema version, or null if it has none (a new database).
     */
    public String schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet version = statement.executeQuery("SELECT value FROM dataset_meta WHERE key = 'schema_version'")) {
            return version.next() ? version.getString(1) : null;
        }
    }

    /**
     * Set a {@code dataset_meta} entry.
     */
    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO dataset_meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            upsert.setString(1, key);
            upsert.setString(2, value);
            upsert.executeUpdate();
        }
    }

    private List<String> tableColumns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet info = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (info.next()) {
                columns.add(info.getString("name"));
            }
        }
        return columns;
    }

    /**
     * @return the columns rows of the table are inserted with: all but its INTEGER PRIMARY KEY, which is the rowid.
     * Generated columns are not listed by table_info.
     */
    private List<String> insertColumns(String table) throws SQLException {
        List<String> columns = insertColumns.get(table);
        if (columns == null) {
            columns = new ArrayList<>();
            List<String> key = new ArrayList<>();
            String rowid = null;
            try (Statement statement = connection.createStatement();
                 ResultSet info = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (info.next()) {
                    columns.add(info.getString("name"));
                    if (info.getInt("pk") > 0) {
                        key.add(info.getString("name"));
                        if ("INTEGER".equalsIgnoreCase(info.getString("type"))) {
                            rowid = info.getString("name");
                        }
                    }
                }
            }
            //Only a primary key of one INTEGER column is the rowid.
            if (key.size() == 1 && key.get(0).equals(rowid)) {
                columns.remove(rowid);
            }
            insertColumns.put(table, columns);
        }
        return columns;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Insert one staged trajectory, replacing any earlier one of the same experiment and evalId. Does not commit.
     */
    public void insert(StagedTrajectory staged) throws SQLException {
        if (readOnly) {
            throw new IllegalStateException(file + " is open read-only");
        }
        JsonObject t = staged.trajectory();
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM trajectories WHERE experiment_id = ? AND eval_id = ?")) {
            delete.setString(1, staged.experimentId());
            delete.setString(2, staged.evalId());
            delete.executeUpdate();
        }

        insertRow("trajectories", t);

        Map<String, Long> domIds = new HashMap<>();
        try (PreparedStatement insertDom = connection.prepareStatement("INSERT OR IGNORE INTO dom_snapshots(sha256, html, bytes) VALUES (?, ?, ?)");
             PreparedStatement selectDom = connection.prepareStatement("SELECT id FROM dom_snapshots WHERE sha256 = ?")) {
            for (Map.Entry<String, Object> dom : staged.doms()) {
                String html = (String) dom.getValue();
                insertDom.setString(1, dom.getKey());
                insertDom.setString(2, html);
                insertDom.setLong(3, html.getBytes(StandardCharsets.UTF_8).length);
                insertDom.executeUpdate();
                selectDom.setString(1, dom.getKey());
                try (ResultSet row = selectDom.executeQuery()) {
                    row.next();
                    domIds.put(dom.getKey(), row.getLong(1));
                }
            }
        }

        Map<String, Long> entityIds = new HashMap<>();
        for (Object o : staged.entities()) {
            JsonObject entity = ((JsonObject) o).copy();
            entity.put("trajectory_id", t.getString("id"));
            String sha = entity.getString("dom_sha256");
            entity.put("dom_snapshot_id", sha == null ? null : domIds.get(sha));
            entityIds.put(entity.getString("timeline") + ":" + entity.getInteger("position"),
                    insertRow("timeline_entities", entity));
        }

        Map<Integer, Long> stepIds = new HashMap<>();
        for (Object o : staged.steps()) {
            JsonObject step = ((JsonObject) o).copy();
            step.put("trajectory_id", t.getString("id"));
            step.put("input_observation", entityIds.get(step.getString("input_observation_key")));
            step.put("result_observation", entityIds.get(step.getString("result_observation_key")));
            stepIds.put(step.getInteger("step_number"), insertRow("agent_steps", step));
        }

        for (Object o : staged.links()) {
            JsonObject link = ((JsonObject) o).copy();
            Long stepId = stepIds.get(link.getInteger("step_number"));
            Long entityId = entityIds.get(link.getString("entity_key"));
            if (stepId == null || entityId == null) {
                log.warn("Dropping a link to step {} or entity {} that the trajectory of {} lacks.",
                        link.getInteger("step_number"), link.getString("entity_key"), staged.evalId());
                continue;
            }
            link.put("step_id", stepId).put("entity_id", entityId);
            insertRow("step_entity_links", link);
        }
    }

    /**
     * Insert a row from a JSON object. JSON objects and arrays are stored as JSON text.
     *
     * @return the new row's rowid.
     */
    private long insertRow(String table, JsonObject row) throws SQLException {
        List<String> columns = insertColumns(table);
        String sql = "INSERT INTO %s(%s) VALUES (%s)".formatted(table, String.join(", ", columns),
                String.join(", ", columns.stream().map(c -> "?").toList()));
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int i = 0; i < columns.size(); i++) {
                bind(insert, i + 1, row.getValue(columns.get(i)));
            }
            insert.executeUpdate();
        }
        //The driver does not implement getGeneratedKeys; a connection is only used from one thread.
        try (Statement statement = connection.createStatement(); ResultSet key = statement.executeQuery("SELECT last_insert_rowid()")) {
            return key.next() ? key.getLong(1) : -1;
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof JsonObject || value instanceof JsonArray) {
            statement.setString(index, value.toString());
        } else if (value instanceof Boolean flag) {
            statement.setInt(index, flag ? 1 : 0);
        } else if (value instanceof Integer || value instanceof Long) {
            statement.setLong(index, ((Number) value).longValue());
        } else if (value instanceof Number number) {
            statement.setDouble(index, number.doubleValue());
        } else {
            statement.setString(index, value.toString());
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * @return each experiment in the database, {@code {experiment_id, trajectories, first_collected_at}}, newest first.
     */
    public List<JsonObject> experiments() throws SQLException {
        return query("""
                SELECT experiment_id, COUNT(*) AS trajectories, MIN(collected_at) AS first_collected_at
                FROM trajectories GROUP BY experiment_id ORDER BY first_collected_at DESC""");
    }

    /**
     * @return the trajectories of an experiment, without their large columns, with the size of their charted timeline.
     */
    public List<JsonObject> trajectories(String experimentId) throws SQLException {
        return query("""
                SELECT t.id, t.eval_id, t.experiment_id, t.webarena_task_id, t.task_description, t.execution_mode, t.agent,
                       t.model, t.eval_result, t.score, t.end_reason, t.collected_at,
                       (SELECT COUNT(*) FROM timeline_entities e WHERE e.trajectory_id = t.id AND e.timeline = 'charted') AS charted_entities
                FROM trajectories t WHERE t.experiment_id = ? ORDER BY t.webarena_task_id, t.eval_id""", experimentId);
    }

    /**
     * Read a trajectory back as it was staged: its {@code trajectories} row, the rows of one of its timelines with
     * {@code dom_sha256} in place of {@code dom_snapshot_id}, and those rows' page snapshots. Database ids
     * ({@code id}, {@code trajectory_id}, {@code dom_snapshot_id}) and generated columns are left out of the entity
     * rows; JSON columns are kept as the text they were stored as.
     *
     * @param timeline {@code charted} or {@code uncharted}.
     * @return the trajectory, or null if the database has no trajectory of that id.
     */
    public StagedTrajectory readTrajectory(String trajectoryId, String timeline) throws SQLException {
        List<JsonObject> rows = query("SELECT * FROM trajectories WHERE id = ?", trajectoryId);
        if (rows.isEmpty()) {
            return null;
        }
        JsonObject trajectory = rows.get(0);
        trajectory.remove("success"); //Generated.

        JsonArray entities = new JsonArray();
        JsonObject doms = new JsonObject();
        for (JsonObject entity : query("""
                SELECT e.*, d.sha256 AS dom_sha256, d.html AS dom_html FROM timeline_entities e
                LEFT JOIN dom_snapshots d ON d.id = e.dom_snapshot_id
                WHERE e.trajectory_id = ? AND e.timeline = ? ORDER BY e.position""", trajectoryId, timeline)) {
            entity.remove("id");
            entity.remove("trajectory_id");
            entity.remove("dom_snapshot_id");
            Object html = entity.remove("dom_html");
            if (entity.getString("dom_sha256") != null && html != null) {
                doms.put(entity.getString("dom_sha256"), html);
            }
            entities.add(entity);
        }

        List<String> files = new ArrayList<>();
        for (Object o : entities) {
            JsonObject entity = (JsonObject) o;
            for (String column : List.of("screenshot_path", "crop_path")) {
                String path = entity.getString(column);
                if (path != null) {
                    files.add(path);
                }
            }
        }
        String evalId = trajectory.getString("eval_id");
        return new StagedTrajectory(trajectory.getString("experiment_id"), evalId, TrajectoryWriter.fileStem(evalId),
                trajectory, entities, doms, new JsonArray(), new JsonArray(), files);
    }

    private List<JsonObject> query(String sql, Object... parameters) throws SQLException {
        List<JsonObject> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                bind(statement, i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        Object value = result.getObject(c);
                        if (value instanceof byte[] bytes) {
                            value = new String(bytes, StandardCharsets.UTF_8);
                        }
                        row.put(meta.getColumnLabel(c), value);
                    }
                    rows.add(new JsonObject(row));
                }
            }
        }
        return rows;
    }
}
