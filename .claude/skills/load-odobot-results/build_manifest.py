"""Build a manifest of everything OdoBot and the WebArena scheduler saved for one experiment run.

Usage (from anywhere; stdlib only):
    python -I build_manifest.py <experiment name | run id> --out <dir> [--db <trajectories db copy>]

Writes <out>/manifest.json (machine readable) and <out>/MANIFEST.md (for people), and leaves a copy of the trajectory
database in <out> unless --db is given. The live db/trajectories.db is never opened: when the odobot container runs,
OdoBot is asked for a snapshot (POST /api/trajectories/snapshot); otherwise the file is copied first.
"""
import argparse
import collections
import datetime
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import urllib.request

SKILL_DIR = os.path.dirname(os.path.abspath(__file__))
ODOBOT = os.path.normpath(os.path.join(SKILL_DIR, "..", "..", ".."))
SCHED = os.path.normpath(os.path.join(ODOBOT, "..", "WebArenaScheduler"))
STEM_RE = re.compile(r"^(?P<task>\d+)-(?P<kind>OdoBotNL|[A-Za-z]+)-(?P<uuid>[0-9a-f-]{36})")
RUN_RE = re.compile(r"^wasched-(?P<name>.+)-(?P<ts>\d{8}-\d{6})$")


def resolve_run(arg, sched):
    runs = os.path.join(sched, "runs")
    if os.path.isdir(os.path.join(runs, arg)):
        return arg
    cands = []
    for d in os.listdir(runs):
        m = RUN_RE.match(d)
        if m and m.group("name") == arg:
            cands.append((m.group("ts"), d))
    if not cands:
        sys.exit(f"no run named {arg!r} in {runs} (dry runs end in -fake and are skipped)")
    return max(cands)[1]


def jload(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def jsonl(p):
    with open(p, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def files_of(root):
    out = []
    for dp, _, fs in os.walk(root):
        for f in fs:
            p = os.path.join(dp, f)
            out.append((os.path.relpath(p, root).replace("\\", "/"), os.path.getsize(p)))
    return sorted(out)


def file_kind(name):
    """Classify a per-task file in execution_events/<run-id>/ by its suffix after the stem."""
    if name.endswith("-tokens.json") and not STEM_RE.match(name):
        return None, "experiment_summary"
    m = STEM_RE.match(name)
    if not m:
        return None, None
    stem, rest = m.group(0), name[m.end():]
    if rest == ".json":
        return stem, "event_log"
    if rest.endswith("-tokens.json"):
        return stem, "tokens"
    if rest.endswith("-webarena-eval.json"):
        return stem, "webarena_eval"
    if rest.endswith("-history.json"):
        return stem, "guidance_history"
    if re.search(r"-messages-step-\d+\.json$", rest):
        return stem, "step_messages"
    if rest.endswith("-trajectory.jsonl"):
        return stem, "agent_trajectory"
    return stem, "other:" + rest


def event_counts(path):
    """Count raw OdoX events by (eventType, name); the log is a JSON array with one event per line."""
    c = collections.Counter()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if not line.startswith("{"):
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                c["<unparsed line>"] += 1
                continue
            d = e.get("eventDetails") or {}
            c[f"{e.get('eventType')}/{d.get('name') or d.get('type')}"] += 1
    return dict(c)


def get_db(out, db_arg):
    if db_arg:
        return db_arg, "given"
    live = os.path.join(ODOBOT, "db", "trajectories.db")
    running = False
    try:
        running = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", "odobot"], capture_output=True,
                                 text=True, timeout=20).stdout.strip() == "true"
    except Exception:
        pass
    if running:
        before = set(os.listdir(os.path.join(ODOBOT, "db")))
        req = urllib.request.Request("http://localhost:8076/api/trajectories/snapshot", method="POST")
        urllib.request.urlopen(req, timeout=300).read()
        new = [f for f in os.listdir(os.path.join(ODOBOT, "db")) if f.startswith("trajectories-snapshot") and f not in before]
        if new:
            return os.path.join(ODOBOT, "db", max(new)), "snapshot via OdoBot API (odobot was running)"
    dst = os.path.join(out, "trajectories-copy.db")
    shutil.copyfile(live, dst)
    return dst, "copy of db/trajectories.db (odobot not running)"


def db_section(db, run_id):
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    c.row_factory = sqlite3.Row
    q = lambda s, *a: [dict(r) for r in c.execute(s, a).fetchall()]
    T = "trajectory_id IN (SELECT id FROM trajectories WHERE experiment_id = ?)"
    S = f"step_id IN (SELECT id FROM agent_steps WHERE {T})"
    sec = {
        "dataset_meta": {r["key"]: r["value"] for r in q("SELECT key, value FROM dataset_meta")},
        "experiments_in_db": q("SELECT experiment_id, COUNT(*) n FROM trajectories GROUP BY 1"),
        "run_config": q("""SELECT agent, model, execution_mode, odobot_commit, odox_version, judge_model,
                           reasoning_required, COUNT(*) n FROM trajectories WHERE experiment_id = ? GROUP BY 1,2,3,4,5,6,7""", run_id),
        "eval_results": q("SELECT eval_result, COUNT(*) n FROM trajectories WHERE experiment_id = ? GROUP BY 1", run_id),
        "end_reasons": q("SELECT end_reason, COUNT(*) n FROM trajectories WHERE experiment_id = ? GROUP BY 1", run_id),
        "row_errors": q("""SELECT COUNT(harness_error) harness_errors, COUNT(build_error) build_errors,
                           COUNT(eval_error) eval_errors FROM trajectories WHERE experiment_id = ?""", run_id)[0],
        "entities": q(f"""SELECT timeline, kind, COUNT(*) n, COUNT(screenshot_path) screenshots, COUNT(crop_path) crops,
                          COUNT(dom_snapshot_id) dom_snapshots, COUNT(bbox) bboxes FROM timeline_entities
                          WHERE {T} GROUP BY 1, 2 ORDER BY 1, 2""", run_id),
        "steps": q(f"""SELECT COUNT(*) steps, SUM(lost) lost, SUM(reasoning IS NULL OR reasoning = '') empty_reasoning,
                       SUM(reasoning_complete) reasoning_complete, COUNT(action_crop_path) action_crops,
                       COUNT(prompt_messages) prompt_messages, SUM(prompt_tokens) prompt_tokens,
                       SUM(completion_tokens) completion_tokens, SUM(reasoning_tokens) reasoning_tokens
                       FROM agent_steps WHERE {T}""", run_id)[0],
        "providers": q(f"SELECT provider, reasoning_source, COUNT(*) n FROM agent_steps WHERE {T} GROUP BY 1, 2", run_id),
        "terminals": q(f"SELECT terminal, COUNT(*) n FROM agent_steps WHERE {T} GROUP BY 1", run_id),
        "step_actions": q(f"SELECT json_extract(action, '$.action') action, COUNT(*) n FROM agent_steps WHERE {T} GROUP BY 1 ORDER BY 2 DESC", run_id),
        "links": q(f"SELECT method, COUNT(*) n, ROUND(AVG(confidence), 3) mean_confidence FROM step_entity_links WHERE {S} GROUP BY 1", run_id),
        "interaction_thoughts": q(f"SELECT COUNT(*) n FROM interaction_thoughts WHERE {T}", run_id)[0]["n"],
        "dom_snapshots": q(f"""SELECT COUNT(*) n, SUM(bytes) bytes FROM dom_snapshots
                               WHERE id IN (SELECT dom_snapshot_id FROM timeline_entities WHERE {T})""", run_id)[0],
    }
    rows = q(f"""SELECT t.id trajectory_id, t.eval_id, t.webarena_task_id, t.eval_result, t.score, t.end_reason,
                        t.eval_types, t.collected_at, t.ended_at, t.final_url, t.agent_answer IS NOT NULL has_answer,
                        (SELECT COUNT(*) FROM agent_steps s WHERE s.trajectory_id = t.id) steps,
                        (SELECT COUNT(*) FROM agent_steps s WHERE s.trajectory_id = t.id
                           AND s.emitted = 'uncharted_step' AND s.not_executed IS NULL) executed_steps,
                        (SELECT COUNT(*) FROM agent_steps s WHERE s.trajectory_id = t.id
                           AND json_extract(s.action, '$.action') IN ('left_click', 'double_click', 'triple_click')) click_steps,
                        (SELECT COUNT(*) FROM timeline_entities e WHERE e.trajectory_id = t.id AND e.kind = 'ClickEvent') click_entities,
                        (SELECT COUNT(DISTINCT l.step_id) FROM step_entity_links l JOIN agent_steps s ON s.id = l.step_id
                           WHERE s.trajectory_id = t.id AND l.method = 'spatial') spatially_linked_steps,
                        (SELECT COUNT(*) FROM timeline_entities e WHERE e.trajectory_id = t.id AND e.timeline = 'charted') charted_entities,
                        (SELECT COUNT(*) FROM timeline_entities e WHERE e.trajectory_id = t.id AND e.timeline = 'uncharted') uncharted_observations
                 FROM trajectories t WHERE experiment_id = ? ORDER BY webarena_task_id""", run_id)
    c.close()
    return sec, {r["eval_id"]: r for r in rows}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run", help="experiment name (latest non-dry run of it) or full run id")
    ap.add_argument("--out", required=True)
    ap.add_argument("--db", help="trajectory db copy to read instead of snapshotting/copying")
    ap.add_argument("--sched", default=SCHED)
    ap.add_argument("--odobot", default=ODOBOT)
    ap.add_argument("--no-events", action="store_true", help="skip counting raw events (slow on GB-sized logs)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    run_id = resolve_run(a.run, a.sched)
    name = RUN_RE.match(run_id).group("name") if RUN_RE.match(run_id) else run_id
    run_dir = os.path.join(a.sched, "runs", run_id)
    ev_dir = os.path.join(a.odobot, "execution_events", run_id)
    shot_dir = os.path.join(a.odobot, "db", "screenshots", run_id)
    exp_def = os.path.join(a.sched, "experiments", f"{name}.json")
    m = {"run_id": run_id, "experiment": name, "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
         "locations": {"scheduler_run_dir": run_dir, "experiment_definition": exp_def if os.path.exists(exp_def) else None,
                       "plan": os.path.join(a.sched, "plans", f"{name}.plan.json"),
                       "execution_events": ev_dir, "screenshots": shot_dir,
                       "trajectory_db_live": os.path.join(a.odobot, "db", "trajectories.db")}}

    # --- scheduler side
    run_files = files_of(run_dir)
    ledger = jsonl(os.path.join(run_dir, "ledger.jsonl"))
    report = jload(os.path.join(run_dir, "report.json"))
    m["scheduler"] = {
        "run": jload(os.path.join(run_dir, "run.json")),
        "files": [{"path": p, "bytes": b} for p, b in run_files if not p.startswith("requests/")],
        "request_files": sum(1 for p, _ in run_files if p.startswith("requests/")),
        "ledger_events": dict(collections.Counter(r["ev"] for r in ledger)),
        "report": {k: report.get(k) for k in ("tasks_final", "tasks_planned", "makespan_min", "avg_concurrency", "classes",
                                               "retries", "lost", "auth_mode", "reset_after", "trajectories")},
        "webarena": {k: v for k, v in (report.get("webarena") or {}).items() if k != "by_template"},
    }
    if os.path.exists(exp_def):
        e = jload(exp_def)
        m["scheduler"]["experiment_selection"] = {"source": e.get("source"), "selection": e.get("selection")}
    cfg = jload(os.path.join(run_dir, "config.snapshot.json")).get("odobot", {})
    m["scheduler"]["odobot_config"] = {k: cfg.get(k) for k in ("agent", "max_steps", "timeout_ms", "require_full_reasoning",
                                                                "llm", "extra", "auth", "evaluation")}

    # attempts per task, from the ledger
    tasks = collections.OrderedDict()
    for r in ledger:
        if r["ev"] == "task_submitted":
            att = int(re.search(r"-a(\d+)\.json$", r["request"]).group(1))
            tasks[(r["task"], att)] = {"task": r["task"], "attempt": att, "eval_id": r["eval_id"], "request": r["request"]}
        elif r["ev"] == "task_finished":
            t = tasks.setdefault((r["task"], r["attempt"]), {"task": r["task"], "attempt": r["attempt"]})
            t.update({k: r.get(k) for k in ("type", "iid", "outcome", "class", "eval_result", "score", "steps", "answer",
                                              "final_url", "occupancy_s", "harness_error", "eval_error")})
            t["tokens"] = (r.get("tokens") or {}).get("totalTokens")
            t["executionMs"] = (r.get("timing") or {}).get("executionMs")
    final_attempt = {}
    for (task, att) in tasks:
        final_attempt[task] = max(att, final_attempt.get(task, 0))

    # --- OdoBot execution_events
    per_stem = collections.defaultdict(lambda: {"files": collections.Counter(), "bytes": collections.Counter(), "where": set()})
    ev_files = files_of(ev_dir)
    other_files = []
    for rel, size in ev_files:
        base = rel.rsplit("/", 1)[-1]
        stem, kind = file_kind(base)
        if stem is None:
            other_files.append({"path": rel, "bytes": size})
            continue
        where = "top" if "/" not in rel else rel.rsplit("/", 1)[0]
        key = (stem, where)
        per_stem[key]["files"][kind] += 1
        per_stem[key]["bytes"][kind] += size
    m["execution_events"] = {"files": len(ev_files), "bytes": sum(b for _, b in ev_files), "non_task_files": other_files,
                             "file_kinds": dict(collections.Counter(file_kind(p.rsplit('/', 1)[-1])[1] for p, _ in ev_files))}

    # --- screenshots
    shots = collections.defaultdict(collections.Counter)
    for rel, _ in files_of(shot_dir) if os.path.isdir(shot_dir) else []:
        stem, fname = rel.split("/", 1)
        k = re.sub(r"-?\d+", "-N", fname.rsplit(".", 1)[0]).replace("--", "-")
        shots[stem][k] += 1
    m["screenshots"] = {"folders": len(shots), "files": sum(sum(c.values()) for c in shots.values()),
                        "by_kind": dict(sum(shots.values(), collections.Counter()))}

    # --- trajectory db
    db, how = get_db(a.out, a.db)
    m["trajectory_db"], rows = db_section(db, run_id)
    m["trajectory_db"]["read_from"] = db
    m["trajectory_db"]["read_how"] = how

    # --- per-task table and cross-checks
    table = []
    for (task, att), t in tasks.items():
        stem = t.get("eval_id", "").replace("|", "-")
        where = "top" if att == final_attempt[task] else f"attempts/{task}/a{att}"
        ps = per_stem.get((stem, where), {"files": collections.Counter(), "bytes": collections.Counter()})
        row = dict(t, stem=stem, final=att == final_attempt[task], files_location=where,
                   file_kinds=dict(ps["files"]), event_log_bytes=ps["bytes"].get("event_log", 0),
                   screenshot_files=dict(shots.get(stem, {})) if att == final_attempt[task] else {})
        if not a.no_events and ps["files"].get("event_log"):
            p = os.path.join(ev_dir, *([] if where == "top" else where.split("/")), stem + ".json")
            row["raw_events"] = event_counts(p)
        # retries reuse the eval id, and the database keeps one row per eval id: the final attempt's
        row["trajectory_row"] = rows.get(t.get("eval_id")) if row["final"] else None
        table.append(row)
    m["tasks"] = table

    finals = [r for r in table if r["final"]]
    checks = {
        "final_tasks": len(finals),
        "final_without_trajectory_row": [r["task"] for r in finals if not r["trajectory_row"]],
        "final_missing_files": {r["task"]: [k for k in ("event_log", "tokens", "webarena_eval", "guidance_history",
                                                        "step_messages", "agent_trajectory") if not r["file_kinds"].get(k)]
                                for r in finals},
        "final_without_screenshots": [r["task"] for r in finals if not r["screenshot_files"]],
        "ledger_vs_db_eval_result_mismatch": [r["task"] for r in finals if r["trajectory_row"]
                                              and r["trajectory_row"]["eval_result"] != r.get("eval_result")],
        # agent_steps holds every model reply: executed steps, the terminal reply (emitted TaskComplete/GiveUp) and
        # the reply past the step cap (not_executed). The ledger's steps counts executed steps only.
        "ledger_steps_vs_db_executed_steps_mismatch": [(r["task"], r.get("steps"), r["trajectory_row"]["executed_steps"])
                                                       for r in finals if r["trajectory_row"]
                                                       and r.get("steps") != r["trajectory_row"]["executed_steps"]],
        # clicks the model made that OdoX never logged as ELEMENT_CLICK (see the element-click-capture open issue)
        "click_steps_without_click_events": [(r["task"], r.get("type"), r["trajectory_row"]["click_steps"])
                                             for r in finals if r["trajectory_row"] and r["trajectory_row"]["click_steps"]
                                             and not r["trajectory_row"]["click_entities"]],
        "retried_tasks": sorted({r["task"] for r in table if not r["final"]}),
        "orphan_screenshot_folders": sorted(set(shots) - {r["stem"] for r in finals}),
    }
    checks["final_missing_files"] = {k: v for k, v in checks["final_missing_files"].items() if v}
    try:
        hits = subprocess.run(["grep", "-rlE", "sk-[A-Za-z0-9_-]{20,}", run_dir, ev_dir], capture_output=True, text=True).stdout.split()
        checks["files_with_api_key_like_strings"] = hits
    except Exception as ex:
        checks["files_with_api_key_like_strings"] = f"not checked: {ex}"
    m["checks"] = checks

    with open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1, default=str)
    with open(os.path.join(a.out, "MANIFEST.md"), "w", encoding="utf-8") as f:
        f.write(render_md(m))
    print(os.path.join(a.out, "MANIFEST.md"))


def mb(b):
    return f"{b / 1e6:,.1f} MB"


def render_md(m):
    s, w, d, c = m["scheduler"], m["scheduler"]["webarena"], m["trajectory_db"], m["checks"]
    L = [f"# Manifest: {m['experiment']} ({m['run_id']})", "", f"Generated {m['generated_at']} by build_manifest.py.", "",
         "## Where everything is", ""]
    L += [f"- **{k}**: `{v}`" for k, v in m["locations"].items()]
    L += ["", "## Run summary", "",
          f"- tasks: {s['report']['tasks_final']}/{s['report']['tasks_planned']}, makespan {s['report']['makespan_min']} min, "
          f"avg concurrency {s['report']['avg_concurrency']}, retries {s['report']['retries']}, classes {s['report']['classes']}",
          f"- WebArena: {w.get('pass')}/{w.get('tasks')} passed (success rate {w.get('success_rate')}), errors {w.get('error')}",
          f"- by site: " + ", ".join(f"{k} {v['pass']}/{v['tasks']}" for k, v in (w.get("by_type") or {}).items()),
          f"- by eval type: " + ", ".join(f"{k} {v['pass']}/{v['tasks']}" for k, v in (w.get("by_eval_type") or {}).items()),
          f"- auth {s['report']['auth_mode']}, reset_after {s['report']['reset_after']}; trajectories {s['report']['trajectories']}"]
    for r in d["run_config"]:
        L.append(f"- agent {r['agent']}, model {r['model']}, judge {r['judge_model']}, OdoBot {r['odobot_commit'][:10]}, "
                 f"OdoX {r['odox_version']}, full reasoning required {bool(r['reasoning_required'])}")
    sel = (s.get("experiment_selection") or {}).get("selection") or {}
    if sel:
        L.append(f"- selection: total {sel.get('total')}, sites {sel.get('types')}, distribution {sel.get('distribution')}, "
                 f"seed {sel.get('seed')}, excluded flags {sel.get('exclude_flags')}")
    L += ["", "## Scheduler run directory", "", "| file | bytes |", "|---|---|"]
    L += [f"| `{f['path']}` | {f['bytes']:,} |" for f in s["files"]]
    L += [f"| `requests/*.json` ({s['request_files']} files, one per attempt) | |", "",
          "Ledger events: " + ", ".join(f"{k} {v}" for k, v in s["ledger_events"].items()), "",
          "## OdoBot execution_events", "",
          f"{m['execution_events']['files']} files, {mb(m['execution_events']['bytes'])}. Kinds: " +
          ", ".join(f"{k} {v}" for k, v in m["execution_events"]["file_kinds"].items()), ""]
    L += [f"- other: `{f['path']}` ({f['bytes']:,} B)" for f in m["execution_events"]["non_task_files"]]
    L += ["", "## Screenshots", "", f"{m['screenshots']['files']} files in {m['screenshots']['folders']} task folders: " +
          ", ".join(f"{k} {v}" for k, v in sorted(m["screenshots"]["by_kind"].items())), "",
          "## Trajectory database", "", f"Read from `{d['read_from']}` ({d['read_how']}); schema {d['dataset_meta'].get('schema_version')}.", "",
          "| timeline | kind | rows | screenshots | crops | DOM snapshots | bboxes |", "|---|---|---|---|---|---|---|"]
    L += [f"| {r['timeline']} | {r['kind']} | {r['n']} | {r['screenshots']} | {r['crops']} | {r['dom_snapshots']} | {r['bboxes']} |" for r in d["entities"]]
    st = d["steps"]
    L += ["", f"- agent_steps: {st['steps']} (lost {st['lost']}, empty reasoning {st['empty_reasoning']}, reasoning complete "
              f"{st['reasoning_complete']}, action crops {st['action_crops']}, prompts saved {st['prompt_messages']}); tokens prompt "
              f"{st['prompt_tokens']:,}, completion {st['completion_tokens']:,}, reasoning {st['reasoning_tokens']:,}",
          "- actions: " + ", ".join(f"{r['action']} {r['n']}" for r in d["step_actions"]),
          "- terminals: " + ", ".join(f"{r['terminal']} {r['n']}" for r in d["terminals"]),
          "- providers: " + ", ".join(f"{r['provider']} ({r['reasoning_source']}) {r['n']}" for r in d["providers"]),
          "- step_entity_links: " + ", ".join(f"{r['method']} {r['n']} (mean conf {r['mean_confidence']})" for r in d["links"]),
          f"- interaction_thoughts rows: {d['interaction_thoughts']}; DOM snapshots referenced: {d['dom_snapshots']['n']} "
          f"({mb(d['dom_snapshots']['bytes'] or 0)})",
          "- end reasons: " + "; ".join(f"{r['end_reason']} ({r['n']})" for r in d["end_reasons"]),
          "", "## Per task", "",
          "Columns: steps = executed steps (ledger); db rows = agent_steps rows (adds the terminal reply and any reply past "
          "the step cap); clicks = model click steps / ClickEvent entities / steps with a spatial link.", "",
          "| task | att | site | result | score | steps | db rows | clicks | tokens | end | raw events | event log | shots |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in m["tasks"]:
        ev = r.get("raw_events") or {}
        evs = " ".join(f"{k.split('/')[-1]}:{v}" for k, v in sorted(ev.items()))
        tr = r["trajectory_row"] or {}
        clicks = f"{tr['click_steps']}/{tr['click_entities']}/{tr['spatially_linked_steps']}" if tr else ""
        L.append(f"| {r['task']} | {r['attempt']}{'' if r['final'] else ' (superseded)'} | {r.get('type')} | {r.get('eval_result')} | "
                 f"{r.get('score')} | {r.get('steps')} | {tr.get('steps', '')} | {clicks} | {r.get('tokens')} | {r.get('outcome')} | {evs} | {mb(r['event_log_bytes'])} | "
                 f"{sum(r['screenshot_files'].values())} |")
    L += ["", "## Checks", ""] + [f"- {k}: {v}" for k, v in c.items()]
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
