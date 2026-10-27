---
name: load-odobot-results
description: Loading OdoBot experimental results for analysis. Find everything OdoBot and the WebArena scheduler saved for an experiment run (scheduler ledger and report, per-task event logs, agent steps and prompts, token and timing files, WebArena scoring, screenshots, the trajectories.db dataset), build a manifest of it, and load it safely for analysis. Use when the user wants to look at, analyse, inventory, compare or load the results or trajectories of an OdoBot / wasched experiment (e.g. "take a look at the 'apple' run"), or asks what trajectory information a run has.
---

# Loading OdoBot experimental results for analysis

A scheduler run spreads its outputs over three places. They join on the **run id** (`wasched-<name>-<yyyymmdd-hhmmss>`,
also OdoBot's `experimentId`) and, per task, the **eval id** `<waTaskId>|OdoBotNL|<uuid>`, whose file-name form, the
**stem**, is `<waTaskId>-OdoBotNL-<uuid>`.

| Where | What |
|---|---|
| `../WebArenaScheduler/runs/<run-id>/` | the scheduler's view: plan, ledger, report, requests, resource stats |
| `execution_events/<run-id>/` | OdoBot's per-task files (event logs, agent steps, tokens, scoring) |
| `db/trajectories.db` + `db/screenshots/<run-id>/<stem>/` | the trajectory dataset (when the run had `saveTrajectories: true`) |
| `../WebArenaScheduler/experiments/<name>.json`, `plans/<name>.plan.json` | how the tasks were selected and planned |

File tools can't read outside odo-bot in this project; use the shell for the scheduler repo.

## 1. Find the run and build the manifest

The experiment name is not the run id: pick the newest `runs/wasched-<name>-*` that doesn't end in `-fake` (a dry run).
`build_manifest.py` (next to this file, stdlib only) does that and inventories everything:

```bash
S=<scratchpad>
python -I .claude/skills/load-odobot-results/build_manifest.py <name or run id> --out "$S/<name>-manifest"
# again later without re-copying the db:  --db "$S/<name>-manifest/trajectories-copy.db"
# skip streaming the GB event logs:        --no-events
```

It writes `MANIFEST.md` (show this to the user) and `manifest.json` (per task: ledger outcome, file kinds, raw event
counts, screenshot counts, trajectory row), and runs cross-checks: every final task has all its files, a trajectory row
and screenshots; ledger and db agree on result and executed steps; model clicks that OdoX never logged; retries;
API-key-like strings. About 15 s for a 50-task, 1.6 GB run.

**The trajectory db:** never open the live `db/trajectories.db` while OdoBot runs (Windows bind-mount locking is
unreliable; a reader can roll back the writer's journal). The script asks the running `odobot` container for a snapshot
(`POST /api/trajectories/snapshot`, lands in `db/trajectories-snapshot-<ts>.db`), or copies the file when the container
is stopped. Analyse that copy. Before trusting it, `report.md`'s `trajectories:` line should show everything committed
(`trajectories_settled` in the ledger; `curl -s localhost:8076/api/trajectories/status` → `staged` 0).

## 2. What each artifact holds

### Scheduler run directory
- `report.md` / `report.json`: outcomes and classes, WebArena pass rate (overall, `by_type` = site, `by_eval_type`,
  `by_template`), makespan, concurrency, auth mode, reset policy, trajectories commit summary.
- `ledger.jsonl` (field `ev`): `task_submitted` (eval_id, request file), `task_finished` (outcome, class,
  eval_result, score, executed `steps`, answer, final_url, tokens, timing, instance), `task_retry`, `trajectory_staged`
  / `trajectory_committed`, instance lifecycle. One row per attempt.
- `requests/<task>-a<attempt>.json`: the exact `POST /api/evaluate` body (task, llm settings with the key masked,
  judge, `saveTrajectories`). `config.snapshot.json`: scheduler config, secrets masked. `plan.json`, `run.json`,
  `state.json`. `stats.jsonl` / `env-events.jsonl`: per-container CPU/RAM samples and stack lifecycle (performance only).

### `execution_events/<run-id>/` (one set per task, by stem)
- `<stem>.json`: the raw OdoX event log, a JSON array with **one event per line** (stream it; some are 0.5 GB).
  `eventType` `interactionEvent` (`ELEMENT_CLICK`, `INPUT_CHANGE`, `SELECT`, ...) or `customEvent` (`DOM_EFFECT`,
  `NETWORK_EVENT` with full request headers, `OBSERVATION` with a base64 screenshot, `TASK_ANSWER`). Holds only the
  start observation: uncharted per-step observations are in the db, not here. Ends when the agent stops.
- `<stem>-qwen38-trajectory.jsonl`: one line per model reply: `step_num`, full `response` (with `<think>`),
  `low_level_instruction`, parsed `instruction`/action, `terminal`, `answer`.
- `<stem>-qwen38-messages-step-<n>.json`: the prompt sent at step n (system, task, history). **Images are stripped**
  (`image_url` is empty); screenshots are in `db/screenshots`.
- `<stem>-GuidanceConnectionManager-history.json`: harness ↔ OdoX protocol messages (`EXECUTE`, `EXECUTE_DONE`,
  `PAGE_READY`, `PAGE_GONE`) with instruction ids and timing.
- `<stem>-tokens.json`: agent tokens by call type, `timing` phases (setup, auth, execution, inference, scoring),
  outcome, steps, `finalUrl`, auth status, `webarena` result, `evaluatorUsage` (judge tokens, not the agent's).
- `<stem>-webarena-eval.json`: the scored answer, each evaluator's inputs and score, program_html targets, judge
  prompts and replies, REST helper calls.
- `results/<run-id>-tokens.json`: run-wide token and timing summary.
- `attempts/<task>/a<n>/`: files of superseded attempts after a retry (the top level holds the final attempt).

### `db/trajectories.db` (schema: `src/main/java/ca/ualberta/odobot/trajectory/TrajectorySchema.java`; design notes in `docs/capturing-trajectories-at-scale/index.html`)
- `trajectories`: one row per task (`experiment_id` = run id, `eval_id`, `webarena_task_id`): task text and
  definition (no cookies), agent/model/config, OdoBot commit, OdoX version, end reason, `eval_result`, `success`,
  `score`, `agent_answer`, `eval_types`, full `eval_report`. **Final attempt only** (unique on experiment + eval id).
  Schema 3 (since 2026-10-27) adds `notes` here and on `timeline_entities`: a person's notes from the Trajectory
  Debugger, NULL for agent runs. A debugger export (`debugger-<ts>.zip`) is a database of this schema with
  `execution_mode = HUMAN` rows, charted entities only, and its screenshots laid out as under `db/`.
- `agent_steps`: every model reply: `reasoning` (full, `reasoning_complete`), `raw_response`, `low_level_instruction`,
  `action` JSON, `terminal`, `answer`, `emitted` (`uncharted_step`, `TaskComplete`, `GiveUp`), `not_executed` (reply
  past the step cap), `prompt_messages`, `input_observation` / `result_observation` (entity ids), instruction send/done
  times, seqs, `provider`, token counts, `action_crop_path`. So rows = executed steps + terminal reply (+1 past the cap).
- `timeline_entities`: `timeline='charted'` (ClickEvent, DataEntry, CheckboxEvent, SelectEvent, Effect,
  NetworkEvent, ApplicationLocationChange, TaskAnswer, the start Observation) with xpath, tag, bbox, screenshot + crop,
  `dom_snapshot_id`, `details` JSON; `timeline='uncharted'`: one Observation per step (screenshot + DOM snapshot).
- `dom_snapshots`: deduplicated readable page HTML (sha256). `step_entity_links`: step → charted entity, `method`
  `seq_window` (happened during the step, confidence = share of seqs in the window) or `spatial` (the model's click
  point fell in the element's bbox). View `interaction_thoughts`: the step and reasoning behind each charted entity.
- Paths in the db are relative to `db/` (`screenshots/<run-id>/<stem>/observation-N.png`, `step-N-action.png`,
  `element_click-N[-crop].png`, `input_change-N[-crop].png`, `select-N[-crop].png`).

## 3. Loading for analysis

```python
import sqlite3, json
c = sqlite3.connect(f"file:{copy}?mode=ro", uri=True); c.row_factory = sqlite3.Row
X = "<run-id>"
tasks = c.execute("SELECT * FROM trajectories WHERE experiment_id = ?", (X,)).fetchall()
steps = c.execute("""SELECT t.webarena_task_id, t.eval_result, s.step_number, s.reasoning, s.low_level_instruction,
                            json_extract(s.action, '$.action') act, s.terminal
                     FROM agent_steps s JOIN trajectories t ON t.id = s.trajectory_id
                     WHERE t.experiment_id = ? ORDER BY 1, 3""", (X,)).fetchall()
thoughts = c.execute("""SELECT * FROM interaction_thoughts WHERE trajectory_id IN
                        (SELECT id FROM trajectories WHERE experiment_id = ?)""", (X,)).fetchall()
```

Raw events, without loading a whole log: `for line in open(p): line = line.strip().rstrip(","); if line.startswith("{"): e = json.loads(line)`.
Screenshots: `os.path.join(odobot, "db", row["screenshot_path"])`.

Join the scheduler side with `ledger task_finished` (task + attempt) → `task_submitted.eval_id` → `trajectories.eval_id`.

## Caveats

- **Clocks differ:** charted entities use browser time, uncharted observations server time; order by seq, not time.
- **Click capture gaps:** some Magento admin (`shopping_admin`) clicks are never logged as `ELEMENT_CLICK`, so those
  steps have no ClickEvent and no `spatial` link (the manifest's `click_steps_without_click_events`). Don't read a
  missing link as the model missing its target there.
- **Step counts:** the ledger's `steps` counts executed steps; `agent_steps` adds the terminal reply and, at the step
  cap, one reply marked `not_executed`.
- **Sensitive data:** `NETWORK_EVENT` request headers carry session cookies of the WebArena apps; `requests/` and
  `config.snapshot.json` are masked. Check before sharing anything outside the machine.
- **Runs before 2026-10-23** have no model reasoning; runs before 2026-10-26 log clicks under older event names
  (`LINK`, `BTN`, ...) instead of `ELEMENT_CLICK`; runs without `saveTrajectories` have no db rows or screenshots, so
  only the `execution_events` files exist (the manifest still covers those).
- A dry run (`-fake`) has scheduler files only.
