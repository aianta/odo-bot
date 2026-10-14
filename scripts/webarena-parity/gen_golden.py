"""Generate the golden files that OdoBot's WebArena evaluator port is tested against.

Every expected value comes from WebArena's own code (evaluation_harness), NLTK, Playwright's Python client and CPython,
run in the WebArena environment. The Java tests in src/test/java/ca/ualberta/odobot/task/webarena read these files and
require exactly the same results.

Run it in WSL, from anywhere:

    ~/miniconda3/envs/webarena/bin/python -I gen_golden.py \
        --webarena /home/aianta/shock_and_awe/WebArena \
        --out /mnt/c/.../odo-bot/src/test/resources/webarena/golden

Nothing here needs a browser, a WebArena site or an LLM: the page, the REST helpers and the judge are stubbed, and the
stubs' answers are written to the golden files so the Java side replays the same ones.
"""
import argparse
import copy
import gzip
import hashlib
import html
import json
import math
import os
import random
import sys
import tempfile
from pathlib import Path

# Fake site URLs, substituted into test.raw.json exactly as WebArena's scripts/generate_test_data.py does.
SITES = {
    "REDDIT": "http://reddit.test:9999",
    "SHOPPING": "http://shop.test:7770",
    "SHOPPING_ADMIN": "http://shopadmin.test:7780/admin",
    "GITLAB": "http://gitlab.test:8023",
    "WIKIPEDIA": "http://wiki.test:8888/wikipedia_en_all_maxi_2022-05/A/User:The_other_Kiwix_guy/Landing",
    "MAP": "http://map.test:3000",
    "HOMEPAGE": "http://home.test:4399",
}


def substitute(raw: str) -> str:
    raw = raw.replace("__GITLAB__", SITES["GITLAB"])
    raw = raw.replace("__REDDIT__", SITES["REDDIT"])
    raw = raw.replace("__SHOPPING__", SITES["SHOPPING"])
    raw = raw.replace("__SHOPPING_ADMIN__", SITES["SHOPPING_ADMIN"])
    raw = raw.replace("__WIKIPEDIA__", SITES["WIKIPEDIA"])
    raw = raw.replace("__MAP__", SITES["MAP"])
    return raw


def stable_choice(key: str, options: list):
    h = int(hashlib.sha256(key.encode("utf-8")).hexdigest(), 16)
    return options[h % len(options)]


# ------------------------------------------------------------------------------------------------ string variants
WHITESPACE = [" ", "\t", "\n", " ", " ", "　", "\x1c", "​"]


def answer_variants(ref: str) -> list[str]:
    """Predictions around a reference value: exact, case, quotes, whitespace, embedded, near misses."""
    v = [ref, ref.upper(), ref.lower(), f"'{ref}'", f'"{ref}"', f"'{ref}", f" {ref} ", f"\n{ref}\t",
         f" {ref} ", f"​{ref}", f"The answer is {ref}.", f"{ref}0", f"0{ref}", ref[:-1], ref[1:],
         f"'{ref}'".upper(), f"\"'{ref}'\"", "", "N/A", "'", '"', "''"]
    if len(ref) == 1:
        v += [f"There are {ref}. That's it.", f"{ref}.", f"({ref})", f"{ref},{ref}", f"1{ref}", f"{ref}%",
              f"about {ref} items", f"{ref}-ish", f"Answer: {ref}!", f"I found {ref} results.\nDone",
              f"It is {ref}... maybe", f"No. {ref} is the count", f"Mr. {ref} Smith", f"{ref}'s", f"`{ref}`",
              f"“{ref}”", f"{ref}—{ref}", f"total={ref}", f"{ref}/{ref}", f"#{ref}", f"${ref}",
              f"{ref}.0", f"{ref}.{ref}", f"e.g. {ref} or more", f"It's {ref}.\n\nAnother paragraph.",
              f"U.S. had {ref}. The end.", f"Is it {ref}? Yes.", f"{ref} dollars", f"[{ref}]", f"{ref}:{ref}"]
    return v


TOKENIZE_CORPUS = [
    "Good muffins cost $3.88\nin New York.  Please buy me\ntwo of them.\nThanks.",
    "They'll save and invest more.", "hi, my name can't hello,", "The answer is 2. That's all.",
    "Mr. Smith went to Washington. He arrived at 5 p.m. on Jan. 3rd.", "U.S.A. is a country. So is Canada.",
    "\"Quoted,\" she said. 'Single quoted' too.", "Wait... what?! No way.", "(Sent1.) Sent2.", "e.g. this, i.e. that.",
    "Numbers: 1,234.56 and -7 and .5 and 3.", "gonna wanna gimme lemme cannot 'tis 'twas", "A -- B --- C",
    "«Bonjour» “smart quotes” ‘single’ „low“", "It costs 20% more & @home #1 *starred*", "en–dash em—dash ‒ ―",
    "Done!!! Really?? Yes.", "Line one\n\nLine two after blank", "Order No. 000000190 shipped.", "St. Louis, Mo. is nice.",
    "He said: 'yes'.", "x.y.z.", "...", ".", "", "   ", "a", "0", "N/A", "n/a", "I don't know.", "1. First 2. Second",
    "The rating is 4.5/5.", "Version 2.0.1 released.", "Dr. No. 7 Dr.", "ID: B07DFJ5XKH.", "Cost: $25.99, tax incl.",
]


def rng_floats(n: int, seed: int = 7) -> list[float]:
    r = random.Random(seed)
    out = [0.1, 0.2, 0.3, 1.5, 100.0, 1e16, 1e15, 9999999999999998.0, 1e21, 1e22, 1e-4, 1e-5, 0.0001, 0.00001,
           123456.789, 2 ** 53, 2 ** 53 + 2.0, 1.7976931348623157e308, 5e-324, 2.2250738585072014e-308, 0.5, -0.25,
           3.14159, 1 / 3, 2 / 3, 4.35, 0.07, 1234567.0, 12345678901234567.0]
    for _ in range(n):
        kind = r.random()
        if kind < 0.3:
            out.append(r.uniform(-1000, 1000))
        elif kind < 0.6:
            out.append(r.random() * 10 ** r.randint(-30, 30))
        elif kind < 0.8:
            out.append(round(r.uniform(0, 100), r.randint(0, 6)))
        else:
            out.append(struct_double(r))
    return out


def struct_double(r) -> float:
    import struct
    while True:
        bits = r.getrandbits(64)
        d = struct.unpack("<d", bits.to_bytes(8, "little"))[0]
        if math.isfinite(d):
            return d


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--webarena", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    for k, v in SITES.items():
        os.environ[k] = v
    sys.path.insert(0, args.webarena)
    os.chdir(args.webarena)

    import time as _time
    from evaluation_harness import evaluators as E
    from evaluation_harness import helper_functions as H
    from evaluation_harness.helper_functions import PseudoPage
    from nltk.tokenize import word_tokenize
    from playwright._impl._js_handle import parse_value

    E.time.sleep = lambda s: None  # program_html's hard-coded sleep

    # ------------------------------------------------------------------------------------------------- fake page
    class FakePage(PseudoPage):
        """Stands in for Playwright's Page in HTMLContentEvaluator: scripted evaluate/content, recorded goto."""

        def __init__(self, url: str, script: dict):
            super().__init__(None, url)
            self.script = script
            self.calls = []

        def goto(self, url):
            self.calls.append(["goto", url])
            self.url = url

        def content(self):
            self.calls.append(["content"])
            return self.script["content"]

        def evaluate(self, expression):
            self.calls.append(["evaluate", expression])
            for key, value in self.script["evaluate"]:
                if key in expression:
                    if isinstance(value, dict) and "raise" in value:
                        raise Exception(value["raise"])
                    return value
            raise Exception("no scripted value for " + expression[:80])



    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    raw = Path("config_files/test.raw.json").read_text(encoding="utf-8")
    configs = json.loads(substitute(raw))
    print("configs:", len(configs))

    def write(name, data):
        # Gzipped, with a fixed mtime, so regenerating unchanged data gives identical files.
        payload = json.dumps(data, ensure_ascii=True, indent=0).encode("utf-8")
        with open(out / (name + ".gz"), "wb") as fh:
            with gzip.GzipFile(filename=name, mode="wb", fileobj=fh, mtime=0) as gz:
                gz.write(payload)
        print("wrote", name + ".gz", len(data) if isinstance(data, list) else "")

    tmp = Path(tempfile.mkdtemp())

    def config_file(cfg) -> str:
        p = tmp / f"{cfg['task_id']}.json"
        p.write_text(json.dumps(cfg), encoding="utf-8")
        return str(p)

    # --------------------------------------------------------------------------------------- string functions
    refs = set()
    for c in configs:
        ra = c["eval"].get("reference_answers") or {}
        for k, v in ra.items():
            for x in (v if isinstance(v, list) else [v]):
                refs.add(x)
        for p in c["eval"].get("program_html") or []:
            rc = p["required_contents"]
            for x in (rc.get("must_include") or []):
                refs.update(x.split(" |OR| "))
            if "exact_match" in rc:
                refs.add(rc["exact_match"])
    refs = sorted(refs)
    string_cases = []
    for ref in refs:
        for pred in answer_variants(ref):
            string_cases.append({
                "ref": ref, "pred": pred,
                "clean_ref": E.StringEvaluator.clean_answer(ref),
                "clean_pred": E.StringEvaluator.clean_answer(pred),
                "exact_match": E.StringEvaluator.exact_match(ref=ref, pred=pred),
                "must_include": E.StringEvaluator.must_include(ref=ref, pred=pred, tokenize=False),
                "must_include_tokenize": E.StringEvaluator.must_include(ref=ref, pred=pred, tokenize=True),
            })
    write("string-functions.json", string_cases)

    # ---------------------------------------------------------------------------------------------- tokenizer
    corpus = list(TOKENIZE_CORPUS)
    for ref in refs:
        if len(E.StringEvaluator.clean_answer(ref)) == 1:
            corpus += answer_variants(ref)
    corpus += [c["intent"] for c in configs]
    corpus += [c["eval"].get("string_note") or "" for c in configs]
    corpus += [str(x) for c in configs for x in ((c["eval"].get("reference_answers") or {}).get("must_include") or [])]
    seen = set()
    tok_cases = []
    for text in corpus:
        if text in seen:
            continue
        seen.add(text)
        tok_cases.append({"text": text, "tokens": word_tokenize(text)})
    write("word-tokenize.json", tok_cases)

    # ------------------------------------------------------------------------------------- string evaluator
    replies = {
        "fuzzy": ["correct", "incorrect", "partially correct", "Correct.", "The student answer is correct.",
                  "INCORRECT", "I cannot tell"],
        "ua": ["same", "different", "They are the SAME.", "Different reasons.", "unclear"],
    }
    se_cases = []
    for c in configs:
        if "string_match" not in c["eval"]["eval_types"]:
            continue
        ra = c["eval"]["reference_answers"]
        candidates = []
        for k, v in ra.items():
            for x in (v if isinstance(v, list) else [v]):
                candidates.append(x)
        answers = [candidates[0], " ".join(str(x) for x in candidates), "N/A", f"N/A: {c['eval'].get('string_note')}",
                   "", "something else entirely", str(candidates[0]).upper() + "!"]
        for answer in answers:
            calls = []

            def fake_llm(model, messages, temperature, max_tokens, top_p, context_length, **kw):
                kind = "ua" if "unachievable" in messages[1]["content"] else "fuzzy"
                reply = stable_choice(json.dumps(messages) + str(len(calls)), replies[kind])
                calls.append({"messages": messages, "reply": reply,
                              "params": {"model": model, "temperature": temperature, "max_tokens": max_tokens,
                                         "top_p": top_p}})
                return reply

            H.generate_from_openai_chat_completion = fake_llm
            try:
                score = E.StringEvaluator()([{"answer": answer}], config_file(c))
                result = {"score": score}
            except AssertionError as e:
                result = {"error": "AssertionError"}
            se_cases.append({"task_id": c["task_id"], "config": {"intent": c["intent"], "eval": c["eval"]},
                             "answer": answer, "judge": calls, **result})
    write("string-evaluator.json", se_cases)

    # ----------------------------------------------------------------------------------------- url evaluator
    url_cases = []
    for c in configs:
        if "url_match" not in c["eval"]["eval_types"]:
            continue
        ref_urls = c["eval"]["reference_url"].split(" |OR| ")
        preds = []
        for u in ref_urls:
            preds += [u, u + "/", u + "//", u.rstrip("/"), u + "?extra=1", u + "&extra=1", u + "#frag",
                      u.replace("http://", "HTTP://"), u.replace("%20", "+"), u.replace("+", "%20"),
                      u.replace("=", "=x"), u.split("?")[0], u + ";params", u[:-3], u + "abc",
                      u.replace("://", "://www."), u.replace(".test", ".other"), "prefix-" + u]
            if "?" in u:
                base, q = u.split("?", 1)
                parts = q.split("&")
                preds += [base + "?" + "&".join(reversed(parts)), base + "?" + "&".join(parts[:-1]),
                          base + "/?" + q, base + "?" + q + "&" + q]
        preds += [SITES["GITLAB"], SITES["REDDIT"] + "/", "about:blank", ""]
        for pred in dict.fromkeys(preds):
            page = PseudoPage(None, pred)
            try:
                score = E.URLEvaluator()([{"answer": ""}], config_file(c), page)
                result = {"score": score}
            except Exception as e:
                result = {"error": type(e).__name__}
            url_cases.append({"task_id": c["task_id"], "config": {"eval": c["eval"]}, "url": pred, **result})
    write("url-evaluator.json", url_cases)

    # ---------------------------------------------------------------------------------------- html evaluator
    def scripted_value(target, mode, key):
        rc = target["required_contents"]
        if "exact_match" in rc:
            want = rc["exact_match"]
            return {"pos": want, "case": f"  '{want.upper()}' ", "neg": want + "x", "empty": ""}[mode]
        parts = [x.split(" |OR| ")[-1 if mode == "case" else 0] for x in rc["must_include"]]
        joined = " | ".join(parts)
        return {"pos": f"before {joined} after", "case": joined.upper(), "neg": joined[: len(joined) // 2],
                "empty": ""}[mode]

    html_cases = []
    for c in configs:
        if "program_html" not in c["eval"]["eval_types"]:
            continue
        for mode in ["pos", "case", "neg", "empty", "js_error", "non_string"]:
            targets = c["eval"]["program_html"]
            evaluate = []
            content = ""
            helper_values = {}
            for i, t in enumerate(targets):
                v = scripted_value(t, "pos" if mode in ("js_error", "non_string") else mode, i)
                loc = t["locator"].strip()
                if not loc:
                    content = (content + " " + v) if content else v
                elif loc.startswith("func:"):
                    name = loc[5:].split("(")[0]
                    if name == "gitlab_get_project_memeber_role":
                        evaluate.append(["gl-avatar-labeled-sublabel", 2])
                        evaluate.append(["col-max-role", v])
                    else:
                        helper_values[name] = v
                else:
                    if mode == "js_error":
                        evaluate.append([loc, {"raise": "TypeError: x is null"}])
                    elif mode == "non_string":
                        evaluate.append([loc, stable_choice(loc, [3, 0, True, False, None, 2.5, [v, 1], {"a": v}])])
                    else:
                        evaluate.append([loc, v])
                for prep in t.get("prep_actions") or []:
                    evaluate.insert(0, [prep, {"raise": "prep failed"} if mode == "neg" else None])
            start_url = SITES["REDDIT"] + "/f/books/59421/interesting-book-title/-/comment/12345"
            script = {"content": content, "evaluate": evaluate}
            page = FakePage(start_url, script)
            for name in ["shopping_get_latest_order_url", "shopping_get_sku_latest_review_author",
                         "shopping_get_sku_latest_review_rating"]:
                value = helper_values.get(name, SITES["SHOPPING"] + "/sales/order/view/order_id/190/")
                setattr(E, name, (lambda val: (lambda *a: val))(value))
                helper_values.setdefault(name, value)
            try:
                score = E.HTMLContentEvaluator()([{"answer": ""}], config_file(c), page)
                result = {"score": score}
            except Exception as e:
                result = {"error": type(e).__name__}
            html_cases.append({"task_id": c["task_id"], "mode": mode, "config": {"eval": c["eval"]},
                               "startUrl": start_url, "script": script, "helpers": helper_values,
                               "calls": page.calls, **result})
    write("html-evaluator.json", html_cases)

    # ------------------------------------------------------------------------------------------- func strings
    func_cases = []
    for c in configs:
        for p in c["eval"].get("program_html") or []:
            for field, value in (("url", p["url"]), ("locator", p["locator"])):
                if "func:" not in value:
                    continue
                func = value.split("func:")[1]
                func = func.replace("__last_url__", SITES["REDDIT"] + "/f/x/1/y").replace("__page__", "page")
                import ast
                node = ast.parse(func, mode="eval").body
                fargs = [a.value if isinstance(a, ast.Constant) else a.id for a in node.args]
                func_cases.append({"source": func, "name": node.func.id, "args": fargs})
    write("func-calls.json", func_cases)

    # ------------------------------------------------------------------------------------ reddit_get_post_url
    reddit_urls = [SITES["REDDIT"] + p for p in [
        "/f/books/59421/interesting-book-title", "/f/books/59421/interesting-book-title/-/comment/12345",
        "/f/books/59421", "/f/books", "/f", "/", "", "/user/MarvelsGrantMan136", "/forums", "/f/books/59421/",
        "/f/a/b/c/d?x=1#y", "/g/books/1/x"]] + ["https://other.test/f/x/1/y", "not a url", "/f/x/1/y", "http://h/f/x/1"]
    write("reddit-post-url.json", [{"url": u, "expected": H.reddit_get_post_url(u)} for u in reddit_urls])

    # --------------------------------------------------------------------------------------------- unescape
    unescape_cases = []
    names = sorted(html.entities.html5)
    for name in names:
        unescape_cases.append(f"&{name}")
        if not name.endswith(";"):
            unescape_cases.append(f"&{name}x")
        else:
            unescape_cases.append(f"a&{name[:-1]}b")
    for n in [0, 9, 10, 13, 32, 38, 60, 65, 127, 128, 129, 130, 141, 150, 159, 160, 255, 0xD7FF, 0xD800, 0xDFFF,
              0xE000, 0xFDD0, 0xFFFE, 0xFFFF, 0x1F600, 0x10FFFF, 0x110000, 10 ** 30]:
        unescape_cases += [f"&#{n};", f"&#{n}", f"&#x{n:x};", f"&#X{n:X}", f"x&#{n}y"]
    unescape_cases += ["no entities", "&", "&&", "&;", "&#;", "&#x;", "&unknown;", "&amp", "&ampx", "&amp;amp;",
                       "a &lt;b&gt; c", "&notit;", "&notin;", "&#38;#38;", "&" + "a" * 40 + ";", "&\tamp;",
                       "Tom &amp; Jerry&#39;s &quot;show&quot;", "&nbsp;&copy;&reg;&trade;&hellip;&mdash;"]
    write("unescape.json", [{"input": s, "expected": html.unescape(s)} for s in unescape_cases])

    # --------------------------------------------------------------------- Playwright values and Python str()
    def to_protocol(v):
        """The in-page serialization, as Playwright's server forwards it to the Python client."""
        if isinstance(v, bool):
            return {"b": v}
        if isinstance(v, (int, float)):
            return {"n": v}
        if isinstance(v, str):
            return {"s": v}
        if isinstance(v, dict):
            if "a" in v:
                return {"a": [to_protocol(x) for x in v["a"]], "id": v["id"]}
            if "o" in v:
                return {"o": [{"k": e["k"], "v": to_protocol(e["v"])} for e in v["o"]], "id": v["id"]}
            return v
        return v

    wire_samples = [
        "3", "0", "-1", "3.5", "1e+21", "1e-7", "123456789012345680000", "0.1", "-2.5e-10", "100.0", "true",
        "false", '"plain"', '"it\'s"', '"say \\"hi\\""', '"both \' and \\""', '"tab\\tnl\\ncr\\r"', '"back\\\\slash"',
        '"\\u00e9\\u4e2d\\ud83d\\ude00"', '"\\u200b\\u00a0\\u2028\\u0085\\u007f\\u0001"', '"\\ue000\\ufffe"',
        '"ref: <Node>"', '{"v":"undefined"}', '{"v":"null"}', '{"v":"NaN"}', '{"v":"Infinity"}',
        '{"v":"-Infinity"}', '{"v":"-0"}', '{"a":[],"id":1}', '{"a":["x",1,2.5,true,{"v":"null"}],"id":1}',
        '{"o":[],"id":1}', '{"o":[{"k":"a","v":1},{"k":"b c","v":"it\'s"},{"k":"n","v":{"a":["x"],"id":2}}],"id":1}',
        '{"a":[{"ref":1}],"id":1}', '{"d":"2023-05-01T10:20:30.123Z"}', '{"d":"2023-05-01T10:20:30.000Z"}',
        '{"u":"https://user@example.com:8080/p;x?q=1#f"}', '{"r":{"p":"a+b","f":"gi"}}',
        '{"a":[{"o":[{"k":"x","v":{"a":[1,2],"id":3}}],"id":2}],"id":1}',
    ]
    for f in rng_floats(400):
        wire_samples.append(json.dumps(f))
    for i in range(-5, 30):
        wire_samples.append(json.dumps(10 ** i))
    value_cases = []
    for w in wire_samples:
        value = parse_value(to_protocol(json.loads(w)))
        value_cases.append({"wire": w, "str": str(value)})
    write("py-values.json", value_cases)

    meta = {"python": sys.version, "sites": SITES}
    try:
        import nltk
        import playwright
        meta["nltk"] = nltk.__version__
        meta["playwright"] = playwright.__version__
    except Exception:
        pass
    write("meta.json", meta)


if __name__ == "__main__":
    main()
