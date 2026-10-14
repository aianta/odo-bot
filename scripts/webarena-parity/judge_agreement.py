"""Judge agreement: the same fuzzy_match / ua_match cases through WebArena's llm_fuzzy_match / llm_ua_match and
OdoBot's judge (ParityRunner judge), on the same model. The prompts are already proven identical by the golden tests;
this checks the reply parsing against a real model, whose replies vary a little even at temperature 0.

    python judge_agreement.py make --raw test.raw.json --fuzzy 10 --ua 5 --out cases.json [--model M --base-url U]
    ~/miniconda3/envs/webarena/bin/python judge_agreement.py python cases.json py-judge.json --webarena <repo>
    java -cp odobot.jar ca.ualberta.odobot.task.webarena.ParityRunner judge cases.json java-judge.json
    python judge_agreement.py compare py-judge.json java-judge.json

Both sides read the API key from OPENAI_API_KEY (or the cases' judge.api_key).
"""
import argparse
import json
import os
import random
import sys


def make(a):
    raw = json.load(open(a.raw, encoding="utf-8"))
    fuzzy = [t for t in raw if isinstance((t["eval"].get("reference_answers") or {}).get("fuzzy_match"), list)]
    ua = [t for t in raw if (t["eval"].get("reference_answers") or {}).get("fuzzy_match") == "N/A"]
    rng = random.Random(a.seed)
    cases = []
    for t in rng.sample(fuzzy, min(a.fuzzy, len(fuzzy))):
        ref = t["eval"]["reference_answers"]["fuzzy_match"][0]
        for variant, pred in (("reference", ref), ("wrong", "i could not find it")):
            cases.append({"id": f"{t['task_id']}-{variant}", "kind": "fuzzy", "pred": pred.lower(), "reference": ref,
                          "question": t["intent"]})
    for t in rng.sample(ua, min(a.ua, len(ua))):
        note = t["eval"].get("string_note") or ""
        for variant, pred in (("reason", f"n/a: {note.lower()}"), ("wrong", "n/a: the website was down")):
            cases.append({"id": f"{t['task_id']}-{variant}", "kind": "ua", "pred": pred, "reference": note,
                          "question": t["intent"]})
    judge = {"model": a.model}
    if a.base_url:
        judge["base_url"] = a.base_url
    json.dump({"judge": judge, "cases": cases}, open(a.out, "w", encoding="utf-8"), indent=1)
    print(f"wrote {a.out}: {len(cases)} cases")


def python_side(a):
    data = json.load(open(a.cases, encoding="utf-8"))
    sys.path.insert(0, a.webarena)
    os.chdir(a.webarena)
    for var in ("SHOPPING", "SHOPPING_ADMIN", "REDDIT", "GITLAB", "MAP", "WIKIPEDIA", "HOMEPAGE"):
        os.environ.setdefault(var, "http://unused")
    from evaluation_harness import helper_functions as H
    model = data["judge"].get("model", "gpt-4-1106-preview")
    original = H.generate_from_openai_chat_completion
    replies = {}

    def call(**kw):
        reply = original(**{**kw, "model": model})
        replies["last"] = reply
        return reply

    H.generate_from_openai_chat_completion = call
    results = []
    for c in data["cases"]:
        fn = H.llm_ua_match if c["kind"] == "ua" else H.llm_fuzzy_match
        r = {"id": c["id"]}
        try:
            r["score"] = fn(c["pred"], c["reference"], c["question"])
            r["reply"] = replies.get("last")
        except Exception as e:  # noqa: BLE001
            r["error"] = repr(e)
        results.append(r)
    json.dump({"side": "python", "model": model, "results": results}, open(a.out, "w", encoding="utf-8"), indent=1)
    print("wrote", a.out)


def compare(a):
    py = {r["id"]: r for r in json.load(open(a.python, encoding="utf-8"))["results"]}
    java = {r["id"]: r for r in json.load(open(a.java, encoding="utf-8"))["results"]}
    agree = 0
    for cid in sorted(py):
        p, j = py[cid], java.get(cid, {})
        same = p.get("score") == j.get("score") and ("error" in p) == ("error" in j)
        agree += same
        if not same:
            print(f"{cid}: python {p.get('score', p.get('error'))} ({p.get('reply')!r}) vs "
                  f"java {j.get('score', j.get('error'))} ({j.get('reply')!r})")
    print(f"{agree}/{len(py)} cases agree ({agree / max(1, len(py)):.0%})")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--raw", required=True)
    m.add_argument("--fuzzy", type=int, default=10)
    m.add_argument("--ua", type=int, default=5)
    m.add_argument("--model", default="gpt-4-1106-preview")
    m.add_argument("--base-url", default=None)
    m.add_argument("--seed", type=int, default=0)
    m.add_argument("--out", required=True)
    p = sub.add_parser("python")
    p.add_argument("cases")
    p.add_argument("out")
    p.add_argument("--webarena", required=True)
    c = sub.add_parser("compare")
    c.add_argument("python")
    c.add_argument("java")
    a = ap.parse_args()
    {"make": make, "python": python_side, "compare": compare}[a.cmd](a)


if __name__ == "__main__":
    main()
