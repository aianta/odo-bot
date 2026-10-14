"""Shadow-score a real OdoBot run with WebArena's own evaluators: for each <evalId>-webarena-eval.json in an experiment
folder, open a logged-in Playwright page at the URL the agent ended on, score the agent's answer with the original
evaluators, and compare with OdoBot's result. Run it before the environment is reset, since program_html reads the
sites' current state.

    ~/miniconda3/envs/webarena/bin/python rescore.py <experiment folder> --raw test.raw.json --sites sites.json \
        --webarena /home/aianta/shock_and_awe/WebArena [--browser firefox]

Known gap: a program_html target with url "last" reads OdoBot's live page, but a freshly loaded one here, so
client-side state that a reload drops (unsaved form fields) can differ.
"""
import argparse
import glob
import json
import os
import sys
import tempfile

from make_sweep import substitute


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--raw", required=True)
    ap.add_argument("--sites", required=True)
    ap.add_argument("--webarena", required=True)
    ap.add_argument("--browser", choices=["firefox", "chromium"], default="firefox")
    ap.add_argument("--judge-model", default=None, help="score fuzzy_match with this model; otherwise stub 'correct'")
    a = ap.parse_args()
    sites = json.load(open(a.sites, encoding="utf-8"))
    raw = {t["task_id"]: t for t in json.load(open(a.raw, encoding="utf-8"))}
    for var, site in {"SHOPPING": "shopping", "SHOPPING_ADMIN": "shopping_admin", "REDDIT": "reddit",
                      "GITLAB": "gitlab", "MAP": "map", "WIKIPEDIA": "wikipedia"}.items():
        os.environ[var] = sites.get(site, f"http://{site}.unused")
    os.environ.setdefault("HOMEPAGE", "http://homepage.unused")
    folder = os.path.abspath(a.folder)
    sys.path.insert(0, a.webarena)
    os.chdir(a.webarena)
    from playwright.sync_api import sync_playwright
    from browser_env.auto_login import renew_comb
    from evaluation_harness import evaluator_router
    from evaluation_harness import helper_functions as H

    if a.judge_model is None:
        H.generate_from_openai_chat_completion = lambda **kw: "correct"
    else:
        original = H.generate_from_openai_chat_completion
        H.generate_from_openai_chat_completion = lambda **kw: original(**{**kw, "model": a.judge_model})

    rows = []
    for path in sorted(glob.glob(os.path.join(folder, "*-webarena-eval.json"))):
        ev = json.load(open(path, encoding="utf-8"))
        tid = ev["webarenaTaskId"]
        t = raw[tid]
        cfg = substitute({"intent": t["intent"], "eval": t["eval"]}, sites)
        tmp = tempfile.mkdtemp()
        cfg_path = f"{tmp}/config.json"
        json.dump(cfg, open(cfg_path, "w", encoding="utf-8"))
        state = t.get("storage_state")
        comb = [s for s in state.split("/")[-1].replace("_state.json", "").split(".") if s] if state else []
        storage = None
        if comb:
            renew_comb(sorted(comb), auth_folder=tmp)
            storage = f"{tmp}/{'.'.join(sorted(comb))}_state.json"
        try:
            with sync_playwright() as p:
                browser = getattr(p, a.browser).launch(headless=True)
                page = browser.new_context(storage_state=storage, viewport={"width": 1280, "height": 720}).new_page()
                if ev.get("finalUrl"):
                    page.goto(ev["finalUrl"])
                score = evaluator_router(cfg_path)([{"answer": ev.get("answer", "")}], cfg_path, page, None)
                py = "PASS" if score == 1 else "FAIL"
                browser.close()
        except Exception as e:  # noqa: BLE001
            py = f"ERROR ({type(e).__name__})"
        agree = py.split(" ")[0] == ev["result"]
        rows.append((tid, ev["evalId"], ev["result"], py, agree))
        print(f"task {tid}: odobot {ev['result']}, original {py}{'' if agree else '  <-- DIFFERS'}")
    n = len(rows)
    agree = sum(1 for r in rows if r[4])
    print(f"\n{agree}/{n} tasks agree")
    raise SystemExit(0 if agree == n else 1)


if __name__ == "__main__":
    main()
