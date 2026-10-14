# WebArena evaluator parity

OdoBot scores WebArena tasks with a Java port of WebArena's `evaluation_harness`
(`src/main/java/ca/ualberta/odobot/task/webarena`). These scripts check that the port gives the same results as the
original. The Python side always runs WebArena's own code, in the WebArena environment
(`~/miniconda3/envs/webarena` in WSL: Python 3.10.21, Playwright 1.32.1, NLTK 3.10.3).

## Offline (no browser, no sites): golden files

| Script | Writes | Checked by |
|---|---|---|
| `gen_resources.py` | `src/main/resources/webarena/py-html.json`: CPython's HTML5 entity tables | `PyHtml` loads it |
| `gen_golden.py` | `src/test/resources/webarena/golden/*.json.gz`: the original's results over all 812 configs | `PyCompatGoldenTest`, `EvaluatorGoldenTest` |

```bash
~/miniconda3/envs/webarena/bin/python -I gen_golden.py --webarena /home/aianta/shock_and_awe/WebArena \
    --out /mnt/c/.../odo-bot/src/test/resources/webarena/golden
```

`gen_golden.py` runs WebArena's evaluators with a scripted page, REST helpers and judge, and records what they were
asked and what they gave. That covers every string rule (including NLTK tokenizing), URL matching, `html.unescape`,
Playwright value formatting, `func:` parsing and the exact judge prompts. Regenerate the files after changing
WebArena, NLTK or Playwright. The punkt tables in `src/main/resources/webarena/punkt_tab/english` are copied as they
are from `~/nltk_data/tokenizers/punkt_tab/english`.

## Live (a browser and the WebArena sites)

The Java side is `ca.ualberta.odobot.task.webarena.ParityRunner`, run from OdoBot's jar:
`java --enable-preview -cp build/libs/odo-bot-1.0-SNAPSHOT-all.jar ca.ualberta.odobot.task.webarena.ParityRunner <mode> in.json out.json`.
It needs a Firefox: a grid from the pinned `selenium/standalone-firefox` image, given as `gridUrl`. Every URL in an
input file must be reachable from that Firefox, and the Python side must reach the same pages.

1. **JS values** (`js_values.py`). The same JS expressions through Playwright plus `str()`, and through
   `SeleniumWebArenaPage` plus `PyRepr`. Only one page is needed. Result on 2026-10-13: 49/49 agree, with Python on
   Chromium (Playwright 1.32's Firefox build is not installed in the env) and Java on Firefox.
2. **Sweep** (`make_sweep.py`, `ParityRunner sweep`, `sweep_python.py`, `compare.py`). Score the same tasks on both
   sides and diff the score, each evaluator, and each program_html target's value and result.
   - `--mode baseline`: an untouched environment, starting at `start_url` with answer "".
   - `--mode reference` (with and without `--corrupt`): string and URL tasks at their reference URL and answer, which
     must give 1 and then 0 on both sides.
   - `--tasks minimal` is the 15-task set that covers every live code path and login combination:
     0, 22, 45, 158, 173, 409, 431, 436, 481, 496, 585, 615, 681, 699, 742.

   ```bash
   python make_sweep.py --raw test.raw.json --sites sites.json --grid http://<firefox>:4444 --out sweep.json
   java ... ParityRunner sweep sweep.json java-out.json
   ~/miniconda3/envs/webarena/bin/python sweep_python.py sweep.json py-out.json --webarena <repo> --browser chromium
   python compare.py java-out.json py-out.json --report sweep-report.md
   ```
   Some positives need site state that an untouched environment lacks: 481 (a member with the role), 585 (the
   review), 436 (the order) and 699 (the rule action). Make that state by hand, sweep, then reset the stack.
3. **Shadow scoring of real runs** (`rescore.py`). After an OdoBot run, and before any reset, re-score every
   `<evalId>-webarena-eval.json` with the original evaluators at the agent's final URL. Run it without `-I`, since it
   imports `make_sweep.py`.
4. **Judge agreement** (`judge_agreement.py`, `ParityRunner judge`). The same fuzzy_match and ua_match cases through
   both judges on one model. Both read `OPENAI_API_KEY`.

Read differences with the browser in mind. OdoBot drives Firefox; WebArena drove Chromium. `innerText` and
`outerText` whitespace, and error messages, can differ between the two. `compare.py` reports a value difference with
the same target result as a warning, not a failure, because full-page content carries CSRF tokens and timestamps.
