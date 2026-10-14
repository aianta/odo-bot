"""Diff a Java sweep (ParityRunner sweep) against a Python sweep (sweep_python.py) of the same sweep file.

    python compare.py java-out.json py-out.json [--report report.md]

For each task: the overall score, each evaluator's score, and for program_html each target's value (by SHA-256 of the
value read off the page) and result. A value difference with the same target result is reported as a warning: full
page content carries CSRF tokens and timestamps that differ between any two page loads. Exit status 1 if any score or
target result differs.
"""
import argparse
import json


def outcome(r):
    return ("error", r["error"].split("(")[0].split(":")[0]) if "error" in r else ("score", r.get("score"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("java")
    ap.add_argument("python")
    ap.add_argument("--report")
    a = ap.parse_args()
    java = {r["taskId"]: r for r in json.load(open(a.java, encoding="utf-8"))["results"]}
    py = {r["taskId"]: r for r in json.load(open(a.python, encoding="utf-8"))["results"]}

    lines = ["| task | java | python | evaluators | targets (value / result) | verdict |", "|---|---|---|---|---|---|"]
    failures = warnings = 0
    for tid in sorted(set(java) | set(py)):
        j, p = java.get(tid), py.get(tid)
        if j is None or p is None:
            lines.append(f"| {tid} | {'-' if j is None else outcome(j)} | {'-' if p is None else outcome(p)} | | | MISSING |")
            failures += 1
            continue
        verdict = "ok"
        if outcome(j)[0] != outcome(p)[0] or (outcome(j)[0] == "score" and outcome(j)[1] != outcome(p)[1]):
            verdict = "SCORE DIFFERS"
        jev = (j.get("trace") or {}).get("evaluators", [])
        pev = p.get("evaluators", [])
        ev_cells = []
        tgt_cells = []
        for k, (je, pe) in enumerate(zip(jev, pev)):
            ev_cells.append(f"{je.get('type')}: {je.get('score', 'err')}/{pe.get('score', 'err')}")
            if je.get("score") != pe.get("score") and verdict == "ok":
                verdict = "EVALUATOR DIFFERS"
            for i, (jt, pt) in enumerate(zip(je.get("targets", []), pe.get("targets", []))):
                same_value = jt.get("selectedElementSha256") == pt.get("selectedElementSha256")
                same_result = jt.get("score") == pt.get("score")
                tgt_cells.append(f"{i}:{'=' if same_value else '~'}/{'=' if same_result else 'X'}")
                if not same_result and verdict == "ok":
                    verdict = "TARGET RESULT DIFFERS"
                elif not same_value:
                    warnings += 1
                    print(f"task {tid} target {i}: values differ, results agree\n"
                          f"  java:   {jt.get('selectedElement', '')[:300]!r}\n  python: {pt.get('selectedElement', '')[:300]!r}")
        if verdict != "ok":
            failures += 1
        lines.append(f"| {tid} | {outcome(j)} | {outcome(p)} | {'; '.join(ev_cells)} | {' '.join(tgt_cells)} | {verdict} |")

    summary = f"{len(set(java) | set(py))} tasks, {failures} differing, {warnings} value-only differences"
    text = "\n".join(lines + ["", summary]) + "\n"
    print(text)
    if a.report:
        open(a.report, "w", encoding="utf-8").write(text)
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
