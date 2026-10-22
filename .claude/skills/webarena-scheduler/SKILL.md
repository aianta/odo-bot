---
name: webarena-scheduler
description: Run WebArena tasks concurrently on OdoBot through the WebArena scheduler (wasched, in ../WebArenaScheduler), with WebArena-faithful login and scoring by OdoBot's port of WebArena's evaluators. Use when the user wants to run a set of WebArena tasks (a smoke test, a sample, a benchmark run) with the uncharted or charted agent, run them in parallel across app stacks, pick the model (OpenRouter, OpenAI, a local server), check scores or success rates, or resume, monitor or clean up a scheduler run.
---

# Running WebArena tasks with the scheduler

The scheduler (`C:\Users\aiant\phdspace\odobleja\WebArenaScheduler`, package `wasched`) turns a selection of WebArena
tasks into a plan, launches the app stacks the tasks need (its own `wa-*` containers, one stack per running task),
Firefox grids, and sends OdoBot one `POST /api/evaluate` per task. OdoBot runs each request in a task verticle of its
own, so several requests run at once on **one** OdoBot (the `odobot` container, API on 8076).

Each request carries the task's WebArena config (`webarena` block), so OdoBot scores the task when the agent stops,
while its browser is still open. With `auth.mode: session` the agent starts logged in, as in WebArena.

Reads outside odo-bot are blocked for the file tools in this project's sessions; work in the scheduler repo through
the shell (`cd ... && .venv/Scripts/python.exe -m wasched ...`).

**Before an end-to-end run:** rebuild OdoBot's image if its code changed, recreate the `odobot` container from it
(below), and **check in with the user** (runs use their machine, model budget and time).

## 1. OdoBot

The scheduler talks to the container named in `odobot.container` (`odobot`), at `odobot.api_url`
(`http://localhost:8076`), reads its outputs through the `execution_events` mount, and gives Firefox its
`odobot-net` address. Recreate it from the current image:

```bash
docker build -t aianta/odobot:latest -f docker-gradle/Dockerfile .      # if the code changed
docker rm -f odobot
docker run -d --name odobot --network odobot-net --ip 172.23.0.2 -p 7080:7080 -p 8076:8076 \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/config:/application/config" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/execution_events:/application/execution_events" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/libs:/application/libs" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/db:/application/db" aianta/odobot:latest
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8076/api/health     # 200 when up
```

Keep `wa-gateway` running: on Docker Desktop it is how the scheduler reaches container IPs. The scheduler starts its
own app containers, so hand-started WebArena apps (`shopping`, `gitlab`, `forum`, ...) only use RAM during a run:
`docker stop` them (reversible with `docker start`).

## 2. Configure (`config/scheduler.yaml`, gitignored)

`python -m wasched setup --check` validates the machine. The keys that matter for a run (all documented in
`config/scheduler.example.yaml`):

```yaml
odobot:
  llm:                           # the agent's model; secrets only as ${ENV_VAR}
    model: qwen/qwen3.8-27b
    base_url: https://openrouter.ai/api/v1
    api_key: ${OPENROUTER_API_KEY}
    timeout_seconds: 600
    max_attempts: 3
    extra_body:                  # OpenRouter: pin providers that keep Qwen's <tool_call> text (see Models)
      provider: {only: [DekaLLM, Wafer, Novita, Alibaba, Phala, CoreWeave], allow_fallbacks: false}
  evaluation:
    enabled: true
    judge:                       # fuzzy_match judge (118 tasks); unset keys = WebArena's gpt-4-1106-preview settings
      model: qwen/qwen3.8-27b
      base_url: https://openrouter.ai/api/v1
      api_key: ${OPENROUTER_API_KEY}
      max_tokens: 4096           # reasoning models need room; WebArena's own is 768
  auth:
    mode: session                # start logged in, as WebArena does; prompt = credentials in the task text
    session_provider: none       # OdoBot logs in by script per task (auto_login.py's steps)
executor:
  max_running_tasks: null        # cap concurrent tasks; set it (e.g. 2) for a local model server
  reset_after: never             # state_changing: fresh stack after program_html tasks; never = WebArena run.py
```

Models:
- **OpenRouter:** the key in `~/.openrouter-key` (ask the user to create it with
  `! printf '%s' '<key>' > ~/.openrouter-key`; never ask them to paste it into the chat). Images work with
  `qwen/qwen3.8-27b`. **Pin the providers** (`extra_body.provider.only`, for the agent and the judge): OpenRouter
  routes each call to one of ~19 providers, and some (seen 2026-10-13: Chutes, Near AI, Reka, Parasail, Cloudflare,
  Venice; since 2026-10-16 also DeepInfra) strip the `<tool_call>` block from the reply. The `-messages-step-N.json`
  dumps omit the screenshot, so to replay a step put the task's first Observation screenshot (from its event log)
  back into the request. The Qwen parser then sees a reply without an action and ends
  the task as done with no answer, which looks like an agent that quits early. Re-test with a replayed request when
  the provider list changes.
- **Local Qwen** (`http://host.docker.internal:8080/v1`): handles few concurrent tasks; set `max_running_tasks: 2`.
- **OpenAI** (`gpt-5.6-luna` for validation): `base_url: null`, `api_key: ${OPENAI_API_KEY}`, and `top_k: null`.

Comparable with published WebArena numbers needs `auth.mode: session`. The run report says whether a run was.

## 3. Select, plan, rehearse

```bash
cd C:/Users/aiant/phdspace/odobleja/WebArenaScheduler
.venv/Scripts/python.exe -m wasched experiment stats --source
.venv/Scripts/python.exe -m wasched experiment select --name <name> --ids 0,157,22,158 --force
.venv/Scripts/python.exe -m wasched experiment select --name <name> --total 40 --distribution equal --seed 7
.venv/Scripts/python.exe -m wasched plan -e experiments/<name>.json      # writes plans/<name>.plan.json
.venv/Scripts/python.exe -m wasched run plans/<name>.plan.json --dry-run fake --time-scale 600   # no docker
```

- `select` drops multi-tab tasks (5) by default and anything on unavailable sites (map, wikipedia here).
- The plan prints RAM per stack type (gitlab ~15 GiB, shopping/shopping_admin ~8, reddit ~6.5) and the phases. Its
  stacks must fit the host (`wasched env host`); one task runs per stack at a time, so concurrency = stacks running.
- A smoke test covering every scoring path in 8 tasks: `0,157` (shopping_admin: exact match, url), `22,158`
  (shopping: N/A judge, url), `45,173` (gitlab: url with query, fuzzy judge + url), `29,409` (reddit: single-digit
  must_include, program_html).

## 4. Run

Run in the background, with the key read into the scheduler's environment only:

```bash
cd C:/Users/aiant/phdspace/odobleja/WebArenaScheduler && \
OPENROUTER_API_KEY="$(cat ~/.openrouter-key)" PYTHONIOENCODING=utf-8 \
  .venv/Scripts/python.exe -m wasched run plans/<name>.plan.json --quiet > <scratchpad>/<name>.log 2>&1
```

The run directory is `runs/<run-id>/` (`wasched-<name>-<timestamp>`); OdoBot's outputs go to
`odo-bot/execution_events/<run-id>/`. Don't poll in a loop; check once in a while:

```bash
.venv/Scripts/python.exe -m wasched status runs/<run-id>
tail -3 runs/<run-id>/ledger.jsonl                     # events: instance_launch/ready, task_assigned/finished, ...
docker logs --tail 50 odobot                           # OdoBot's side
.venv/Scripts/python.exe -m wasched run --resume runs/<run-id>        # after Ctrl+C or a crash
```

GitLab stacks take ~7 min to start; the first tasks wait for their stack.

## 5. Results

- **`runs/<run-id>/report.md`** (and `report.json`): outcomes, the WebArena block (passes, success rate over
  evaluated tasks and over submitted tasks, evaluation errors, judge tokens, per eval type), auth mode, reset policy,
  makespan, concurrency. Rebuild with `wasched report runs/<run-id>`.
- **Ledger `task_finished`** records: `outcome`, `class`, `score`, `eval_result` (PASS/FAIL/ERROR), `answer`,
  `final_url`, `auth`, `judge_tokens`, `timing`.
- **Per task, in `execution_events/<run-id>/`:**
  - `<evalId>-webarena-eval.json`: the scored answer, each evaluator's score, every program_html target's value
    and result, the judge's prompts and replies, REST helper calls, `droppedEvalEvents`.
  - `<evalId>-tokens.json`: `webarena`, `evaluatorUsage` (judge, not the agent's tokens), `auth`, `finalUrl`,
    `timing.authMs`/`scoringMs`.
  - `<evalId>.json`: the event log, which ends when the agent stops (scoring events are dropped).
- **What to check after a smoke test:** every finished task has a `webarena` result; ERRORs have a reason in the
  eval file (`error`); judge tasks have judge calls; program_html targets read real page values; the login
  succeeded (`auth.sites.*.status == "ok"`, a failed login is classed `setup_env` and retried once).
- **Parity spot-check** (optional, before the stacks are torn down): `odo-bot/scripts/webarena-parity/rescore.py`
  re-scores the eval files with WebArena's own evaluators.

## 6. Clean up

The scheduler tears its stacks down at the end (unless `--keep-envs`). Leftovers:

```bash
.venv/Scripts/python.exe -m wasched env list
.venv/Scripts/python.exe -m wasched env down <names...>
docker start shopping shopping_admin gitlab forum      # bring back hand-started apps if the user wants them
```

## Caveats

- **`OdoX did not connect its control and event sockets within 30000 ms`** happens to about 1 task in 5-10, with
  and without session login (seen in mixed9-luna-1 before it existed). It is classed `setup_harness`; the scheduler's
  one retry has always recovered it so far.
- **Secrets on disk:** the scheduler masks secret-looking keys in `config.snapshot.json` and `requests/`, and OdoBot
  masks them when it logs its yaml config. Still check before sharing a run directory:
  `grep -rl "sk-" runs/<run-id> execution_events/<run-id>`.

- **Scores are only as clean as the app state.** `reset_after: never` lets a state-changing task affect later tasks
  on the same stack (and helpers such as "latest order" read global state). Use `state_changing` for clean numbers;
  each reset costs a stack restart (minutes for gitlab).
- **Judge:** with no judge configured, fuzzy_match tasks score ERROR. A different judge model than WebArena's
  gpt-4-1106-preview changes judged scores a little; record it in the experiment notes.
- **Concurrency shares the model:** remote providers rate-limit; `call_llm failed attempt` in `docker logs odobot`
  and `infra_llm` classes in the ledger mean the provider is struggling. Lower `max_running_tasks`.
- **RAM on Windows (WSL2 / Docker Desktop):** plan with `wasched plan ... --budget-ram 30` on this 64 GB machine,
  not the detected 40.9 GiB. Docker's VM keeps page cache (image layers, event logs) that Windows can't reclaim, so
  host free memory can hit a few GB while containers use ~10-20; Claude Code then kills background commands such as
  the scheduler. During a run, drop the cache whenever host free memory falls under ~12 GB:
  `docker run --rm --privileged alpine:3.20 sh -c "sync; echo 3 > /proc/sys/vm/drop_caches"` (non-destructive; a
  20 s Monitor loop works). The lasting fix is `autoMemoryReclaim=dropcache` in `%USERPROFILE%\.wslconfig` (needs
  `wsl --shutdown`, which stops all containers). Stop unrelated containers first.
- **OdoX version matters:** OdoX builds before 2026-10-13 sent one full-page snapshot per DOM mutation; busy admin
  pages then grew one Firefox to 10-25 GiB and 3-4 GB event logs per task. Make sure `odo-sight.xpi` in the image is
  the batched build (one DOM_EFFECT per action per mutation callback, LogUI `verbose: false`).
- **Results of webarena-vanguard (40 tasks, 10 per app, OpenRouter qwen3.8-27b, session auth):** 19/40 (47.5%),
  0 evaluation errors, 1 setup retry, 54 min at ~2.1 concurrent tasks; a reference point for later runs.
