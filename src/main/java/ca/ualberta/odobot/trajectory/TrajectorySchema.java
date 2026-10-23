package ca.ualberta.odobot.trajectory;

import java.util.List;

/**
 * The schema of {@code db/trajectories.db}, see docs/capturing-trajectories-at-scale. Timestamps are epoch
 * milliseconds; JSON is stored as text; file paths are relative to {@code db/}.
 */
final class TrajectorySchema {

    private TrajectorySchema() {
    }

    static final List<String> STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS dataset_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS trajectories (
              id               TEXT PRIMARY KEY,
              eval_id          TEXT NOT NULL,
              experiment_id    TEXT NOT NULL,
              task_id          TEXT,
              webarena_task_id INTEGER,
              task_description TEXT NOT NULL,
              task_definition  TEXT NOT NULL,
              execution_mode   TEXT NOT NULL,
              agent TEXT, model TEXT,
              agent_config     TEXT,
              reasoning_required INTEGER NOT NULL DEFAULT 0,
              odobot_commit TEXT, odox_version TEXT,
              collected_at     INTEGER NOT NULL,
              ended_at INTEGER, end_reason TEXT, harness_error TEXT,
              final_url TEXT,
              eval_result      TEXT CHECK (eval_result IN ('PASS','FAIL','ERROR')),
              success          INTEGER GENERATED ALWAYS AS (CASE eval_result WHEN 'PASS' THEN 1 WHEN 'FAIL' THEN 0 END) VIRTUAL,
              score            REAL,
              eval_error       TEXT,
              agent_answer     TEXT,
              judged_infeasible INTEGER,
              eval_types       TEXT,
              judge_model      TEXT,
              eval_report      TEXT,
              build_error      TEXT,
              UNIQUE (experiment_id, eval_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS dom_snapshots (
              id     INTEGER PRIMARY KEY,
              sha256 TEXT NOT NULL UNIQUE,
              html   TEXT NOT NULL,
              bytes  INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS timeline_entities (
              id            INTEGER PRIMARY KEY,
              trajectory_id TEXT NOT NULL REFERENCES trajectories(id) ON DELETE CASCADE,
              timeline      TEXT NOT NULL CHECK (timeline IN ('charted','uncharted')),
              position      INTEGER NOT NULL,
              kind          TEXT NOT NULL,
              symbol TEXT, event_name TEXT,
              timestamp     INTEGER,
              clock         TEXT,
              first_seq INTEGER, last_seq INTEGER,
              location      TEXT,
              xpath TEXT, tag TEXT, html_id TEXT,
              bbox          TEXT,
              screenshot_path TEXT,
              crop_path     TEXT,
              crop_box      TEXT,
              dom_snapshot_id INTEGER REFERENCES dom_snapshots(id),
              details       TEXT NOT NULL,
              UNIQUE (trajectory_id, timeline, position)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS ix_entities_kind ON timeline_entities(trajectory_id, kind)
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_steps (
              id                 INTEGER PRIMARY KEY,
              trajectory_id      TEXT NOT NULL REFERENCES trajectories(id) ON DELETE CASCADE,
              step_number        INTEGER NOT NULL,
              request_id         TEXT,
              input_observation  INTEGER REFERENCES timeline_entities(id),
              result_observation INTEGER REFERENCES timeline_entities(id),
              raw_response TEXT, reasoning TEXT, committed_response TEXT,
              low_level_instruction TEXT,
              action TEXT, pyautogui TEXT, unsupported TEXT,
              terminal TEXT, answer TEXT,
              emitted            TEXT,
              not_executed       TEXT,
              action_crop_path   TEXT,
              prompt_messages    TEXT,
              instruction_id TEXT, sent_at INTEGER, done_at INTEGER, done_status TEXT, done_reason TEXT, observed_at INTEGER,
              seq_at_send INTEGER, seq_at_observation INTEGER,
              lost INTEGER NOT NULL DEFAULT 0,
              prompt_tokens INTEGER, completion_tokens INTEGER,
              provider           TEXT,
              reasoning_tokens   INTEGER,
              reasoning_source   TEXT,
              reasoning_formats  TEXT,
              reasoning_complete INTEGER,
              reasoning_problem  TEXT,
              original_size TEXT, processed_size TEXT,
              answered_at INTEGER,
              UNIQUE (trajectory_id, step_number)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS step_entity_links (
              step_id    INTEGER NOT NULL REFERENCES agent_steps(id) ON DELETE CASCADE,
              entity_id  INTEGER NOT NULL REFERENCES timeline_entities(id) ON DELETE CASCADE,
              method     TEXT NOT NULL,
              confidence REAL,
              details    TEXT,
              PRIMARY KEY (step_id, entity_id, method)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS ix_links_entity ON step_entity_links(entity_id)
            """,
            """
            CREATE VIEW IF NOT EXISTS interaction_thoughts AS
            SELECT * FROM (
              SELECT e.trajectory_id, e.id AS entity_id, e.position, e.kind, e.event_name, e.xpath,
                     s.id AS step_id, s.step_number, s.low_level_instruction, s.reasoning, l.method, l.confidence,
                     ROW_NUMBER() OVER (PARTITION BY e.id
                       ORDER BY CASE l.method WHEN 'spatial' THEN 0 ELSE 1 END, l.confidence DESC, s.step_number) AS rn
              FROM timeline_entities e
              JOIN step_entity_links l ON l.entity_id = e.id
              JOIN agent_steps s ON s.id = l.step_id
              WHERE e.timeline = 'charted'
            ) WHERE rn = 1
            """
    );

    static final List<String> TRAJECTORY_COLUMNS = List.of(
            "id", "eval_id", "experiment_id", "task_id", "webarena_task_id", "task_description", "task_definition",
            "execution_mode", "agent", "model", "agent_config", "reasoning_required", "odobot_commit", "odox_version",
            "collected_at", "ended_at", "end_reason", "harness_error", "final_url", "eval_result", "score", "eval_error",
            "agent_answer", "judged_infeasible", "eval_types", "judge_model", "eval_report", "build_error");

    static final List<String> ENTITY_COLUMNS = List.of(
            "trajectory_id", "timeline", "position", "kind", "symbol", "event_name", "timestamp", "clock", "first_seq",
            "last_seq", "location", "xpath", "tag", "html_id", "bbox", "screenshot_path", "crop_path", "crop_box",
            "dom_snapshot_id", "details");

    static final List<String> STEP_COLUMNS = List.of(
            "trajectory_id", "step_number", "request_id", "input_observation", "result_observation", "raw_response",
            "reasoning", "committed_response", "low_level_instruction", "action", "pyautogui", "unsupported", "terminal",
            "answer", "emitted", "not_executed", "action_crop_path", "prompt_messages", "instruction_id", "sent_at",
            "done_at", "done_status", "done_reason", "observed_at", "seq_at_send", "seq_at_observation", "lost",
            "prompt_tokens", "completion_tokens", "provider", "reasoning_tokens", "reasoning_source", "reasoning_formats",
            "reasoning_complete", "reasoning_problem", "original_size", "processed_size", "answered_at");

    static final List<String> LINK_COLUMNS = List.of("step_id", "entity_id", "method", "confidence", "details");
}
