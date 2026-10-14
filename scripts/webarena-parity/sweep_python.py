"""The Python side of the live parity sweep: score a sweep file (make_sweep.py) with WebArena's own evaluators, in a
Playwright browser logged in the way WebArena's run.py logs in (auto_login.renew_comb, then a context built from the
storage state), recording every value read off the pages.

    ~/miniconda3/envs/webarena/bin/python sweep_python.py sweep.json py-out.json \
        --webarena /home/aianta/shock_and_awe/WebArena --browser firefox

Use --browser firefox to compare the port itself (OdoBot drives Firefox); --browser chromium (WebArena's browser) to
see how much the browser alone changes, e.g. innerText differences.
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile

TRACE_CHARS = 4000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sweep")
    ap.add_argument("out")
    ap.add_argument("--webarena", required=True)
    ap.add_argument("--browser", choices=["firefox", "chromium"], default="firefox")
    a = ap.parse_args()
    sweep = json.load(open(a.sweep, encoding="utf-8"))
    sites = sweep["sites"]
    env = {"SHOPPING": "shopping", "SHOPPING_ADMIN": "shopping_admin", "REDDIT": "reddit", "GITLAB": "gitlab",
           "MAP": "map", "WIKIPEDIA": "wikipedia"}
    for var, site in env.items():
        os.environ[var] = sites.get(site, f"http://{site}.unused")
    os.environ.setdefault("HOMEPAGE", "http://homepage.unused")
    sys.path.insert(0, a.webarena)
    os.chdir(a.webarena)

    import html
    from playwright.sync_api import sync_playwright
    from browser_env.auto_login import renew_comb
    from evaluation_harness import evaluators as E
    from evaluation_harness import helper_functions as H

    reply = sweep.get("judgeReply", "correct")
    H.generate_from_openai_chat_completion = lambda **kw: reply

    original_unescape = html.unescape
    captured = []

    def capturing_unescape(s):
        v = original_unescape(s)
        captured.append(v)
        return v

    E.html.unescape = capturing_unescape
    kinds = {"string_match": E.StringEvaluator, "url_match": E.URLEvaluator, "program_html": E.HTMLContentEvaluator}

    results = []
    for task in sweep["tasks"]:
        result = {"taskId": task["taskId"]}
        results.append(result)
        tmp = tempfile.mkdtemp()
        state = None
        try:
            if task["loginSites"]:
                renew_comb(task["loginSites"], auth_folder=tmp)
                state = f"{tmp}/{'.'.join(task['loginSites'])}_state.json"
            cfg_path = f"{tmp}/config.json"
            json.dump(task["config"], open(cfg_path, "w", encoding="utf-8"))
            with sync_playwright() as p:
                browser = getattr(p, a.browser).launch(headless=True)
                ctx = browser.new_context(storage_state=state, viewport={"width": 1280, "height": 720})
                page = ctx.new_page()
                page.goto(task["startUrl"])
                result["startUrl"] = page.url
                trajectory = [{"answer": task.get("answer", "")}]
                score = 1.0
                evaluators = []
                for et in task["config"]["eval"]["eval_types"]:
                    captured.clear()
                    ev = {"type": et}
                    evaluators.append(ev)
                    try:
                        s = kinds[et]()(trajectory, cfg_path, page, None)
                        ev["score"] = s
                        score *= s
                    except Exception as e:  # noqa: BLE001
                        ev["error"] = repr(e)
                        result["error"] = repr(e)
                    if et == "program_html":
                        ev["targets"] = [target_record(v, t, E)
                                         for v, t in zip(captured, task["config"]["eval"]["program_html"])]
                    if "error" in result:
                        break
                if "error" not in result:
                    result["score"] = score
                result["evaluators"] = evaluators
                browser.close()
        except Exception as e:  # noqa: BLE001
            result["error"] = "setup: " + repr(e)
        print(f"task {task['taskId']}: {result.get('score', result.get('error'))}")
    json.dump({"side": "python", "browser": a.browser, "results": results}, open(a.out, "w", encoding="utf-8"), indent=1)
    print("wrote", a.out)


def target_record(value, target, E):
    rc = target["required_contents"]
    if "exact_match" in rc:
        score = E.StringEvaluator.exact_match(ref=rc["exact_match"], pred=value)
    else:
        score = 1.0
        for content in rc["must_include"]:
            score *= float(any(E.StringEvaluator.must_include(ref=c, pred=value, tokenize=False)
                               for c in content.split(" |OR| ")))
    return {"selectedElement": value[:TRACE_CHARS], "selectedElementLength": len(value),
            "selectedElementSha256": hashlib.sha256(value.encode("utf-8")).hexdigest(), "score": score}


if __name__ == "__main__":
    main()
