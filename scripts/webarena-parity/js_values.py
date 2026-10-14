"""JS value formatting in a real browser: the same expressions through Playwright's page.evaluate + str() and through
OdoBot's SeleniumWebArenaPage + PyRepr (ParityRunner values). Results must be identical.

    python js_values.py make --url http://<any WebArena page> [--grid http://<firefox>:4444] --out values.json
    java -cp odobot.jar ca.ualberta.odobot.task.webarena.ParityRunner values values.json java-values.json
    ~/miniconda3/envs/webarena/bin/python js_values.py python values.json py-values.json [--browser firefox]
    python js_values.py compare py-values.json java-values.json
"""
import argparse
import json

EXPRESSIONS = [
    "() => null", "() => undefined", "() => true", "() => false", "() => 0", "() => -0", "() => 3", "() => 3.0",
    "() => 3.5", "() => -2.25", "() => 1e21", "() => 1e-7", "() => 123456789012345680000", "() => 0.1 + 0.2",
    "() => NaN", "() => Infinity", "() => -Infinity", "() => 2 ** 53", "() => 'plain'", "() => \"it's\"",
    "() => 'say \"hi\"'", "() => 'both \\' and \"'", "() => 'tab\\tnl\\ncr\\r'", "() => 'back\\\\slash'",
    "() => '\\u00e9\\u4e2d\\ud83d\\ude00'", "() => '\\u200b\\u00a0\\u2028\\u0085\\u007f\\u0001'",
    "() => []", "() => ['x', 1, 2.5, true, null, undefined]", "() => ({})", "() => ({a: 1, 'b c': \"it's\", n: [1]})",
    "() => { const a = [1]; a.push(a); return a; }", "() => new Date(Date.UTC(2023, 4, 1, 10, 20, 30, 123))",
    "() => /a+b/gi", "() => document.body", "() => document", "() => window", "() => Symbol('x')",
    "() => (() => 1)", "() => Promise.resolve('later')", "() => new Error('boom').message",
    "() => document.title", "() => document.querySelectorAll('a').length", "() => document.querySelector('body') !== null",
    "() => [...document.querySelectorAll('a')].slice(0, 3).map(a => a.getAttribute('href'))",
    "() => document.querySelector('nonexistent-element').outerText",
    "() => document.body.outerText.length > 0",
    "() => document.querySelector('nonexistent')?.outerText || ''",
    "(() => 7)()",
    "() => document.title;",
]


def make(a):
    json.dump({"gridUrl": a.grid, "headless": True, "url": a.url, "expressions": EXPRESSIONS},
              open(a.out, "w", encoding="utf-8"), indent=1)
    print(f"wrote {a.out}: {len(EXPRESSIONS)} expressions")


def python_side(a):
    from playwright.sync_api import sync_playwright
    data = json.load(open(a.values, encoding="utf-8"))
    results = []
    with sync_playwright() as p:
        browser = getattr(p, a.browser).launch(headless=True)
        page = browser.new_page()
        page.goto(a.url or data["url"])
        for e in data["expressions"]:
            r = {"expression": e}
            try:
                r["str"] = str(page.evaluate(e))
            except Exception as ex:  # noqa: BLE001
                r["error"] = str(ex).splitlines()[0]
            results.append(r)
        browser.close()
    json.dump({"side": "python", "browser": a.browser, "results": results}, open(a.out, "w", encoding="utf-8"), indent=1)
    print("wrote", a.out)


def compare(a):
    py = json.load(open(a.python, encoding="utf-8"))["results"]
    java = json.load(open(a.java, encoding="utf-8"))["results"]
    same = 0
    for p, j in zip(py, java):
        ok = ("error" in p and "error" in j) or p.get("str") == j.get("str")
        same += ok
        if not ok:
            print(f"{p['expression']}\n  python: {p.get('str', p.get('error'))!r}\n  java:   {j.get('str', j.get('error'))!r}")
    print(f"{same}/{len(py)} expressions agree")
    raise SystemExit(0 if same == len(py) else 1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--url", required=True)
    m.add_argument("--grid", default=None)
    m.add_argument("--out", required=True)
    p = sub.add_parser("python")
    p.add_argument("values")
    p.add_argument("out")
    p.add_argument("--browser", choices=["firefox", "chromium"], default="firefox")
    p.add_argument("--url", default=None, help="the same page, as this machine reaches it")
    c = sub.add_parser("compare")
    c.add_argument("python")
    c.add_argument("java")
    a = ap.parse_args()
    {"make": make, "python": python_side, "compare": compare}[a.cmd](a)


if __name__ == "__main__":
    main()
