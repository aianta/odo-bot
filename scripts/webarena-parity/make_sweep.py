"""Build a sweep file for the live parity checks: the chosen WebArena tasks, with their configs resolved for one
environment, the sites to log in to, the page to start from and the answer to score.

    python make_sweep.py --raw test.raw.json --sites sites.json --tasks minimal --mode baseline --out sweep.json

--sites is a JSON object of WebArena site names to the URLs the browser uses, e.g.
    {"shopping": "http://172.23.10.1", "shopping_admin": "http://172.23.11.1/admin",
     "gitlab": "http://172.23.13.1:8023", "reddit": "http://172.23.12.1"}

--mode baseline: start at the task's start_url and answer "" (nothing was done; most tasks score 0 on both sides,
                 but every locator, navigation and helper is compared on real pages).
--mode reference: start at the first reference URL and answer with the reference answer (string and url tasks
                 should score 1 on both sides); --corrupt also garbles them (should score 0 on both sides).
"""
import argparse
import json

# The minimal live set: every live code path of the evaluators and every login combination, see the plan.
MINIMAL = [0, 22, 45, 158, 173, 409, 431, 436, 481, 496, 585, 615, 681, 699, 742]

PLACEHOLDERS = [("__SHOPPING_ADMIN__", "shopping_admin"), ("__SHOPPING__", "shopping"), ("__GITLAB__", "gitlab"),
                ("__REDDIT__", "reddit"), ("__MAP__", "map"), ("__WIKIPEDIA__", "wikipedia")]


def substitute(obj, sites):
    s = json.dumps(obj)
    for ph, site in PLACEHOLDERS:
        if site in sites:
            s = s.replace(ph, sites[site])
    return json.loads(s)


def reference_answer(ev):
    ra = ev.get("reference_answers") or {}
    if "exact_match" in ra:
        return ra["exact_match"]
    if "must_include" in ra:
        return " ".join(ra["must_include"])
    if "fuzzy_match" in ra:
        v = ra["fuzzy_match"]
        return v if isinstance(v, str) else " ".join(v)
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--sites", required=True)
    ap.add_argument("--tasks", default="minimal", help="'minimal', 'all', or a comma list of task ids")
    ap.add_argument("--mode", choices=["baseline", "reference"], default="baseline")
    ap.add_argument("--corrupt", action="store_true")
    ap.add_argument("--grid", default=None, help="Selenium grid URL for the Java side")
    ap.add_argument("--judge-reply", default="correct", help="stubbed judge reply, the same on both sides")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    raw = {t["task_id"]: t for t in json.load(open(a.raw, encoding="utf-8"))}
    sites = json.load(open(a.sites, encoding="utf-8"))
    ids = MINIMAL if a.tasks == "minimal" else sorted(raw) if a.tasks == "all" else [int(x) for x in a.tasks.split(",")]
    tasks = []
    for tid in ids:
        t = raw[tid]
        if not set(t["sites"]) <= set(sites):
            print(f"skipping task {tid}: sites {t['sites']} not all in --sites")
            continue
        cfg = substitute({"intent": t["intent"], "eval": t["eval"]}, sites)
        start = substitute(t["start_url"], sites).split(" |AND| ")[0]
        answer = ""
        if a.mode == "reference":
            answer = reference_answer(cfg["eval"])
            if cfg["eval"].get("reference_url"):
                start = cfg["eval"]["reference_url"].split(" |OR| ")[0]
            if a.corrupt:
                answer = "zz" + answer[::-1]
                start = start.rstrip("/") + "/zz-not-it"
        state = t.get("storage_state") or ""
        login = [s for s in state.split("/")[-1].replace("_state.json", "").split(".") if s] if state else []
        tasks.append({"taskId": tid, "loginSites": sorted(login), "startUrl": start, "answer": answer, "config": cfg})
    out = {"gridUrl": a.grid, "headless": True, "sites": sites, "pageSettleMs": 3000, "judgeReply": a.judge_reply,
           "mode": a.mode + ("-corrupt" if a.corrupt else ""), "tasks": tasks}
    json.dump(out, open(a.out, "w", encoding="utf-8"), indent=1)
    print(f"wrote {a.out}: {len(tasks)} tasks")


if __name__ == "__main__":
    main()
