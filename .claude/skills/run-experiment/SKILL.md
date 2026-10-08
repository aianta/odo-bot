---
name: run-experiment
description: Execute an OdoBot experiment (an experiment definition JSON sent to POST /api/evaluate) on one or more OdoBot instances, including adapting a definition such as example-experiment.json to a specific environment, running several concurrent experiments, and finding the outputs (events, token usage and timing). Use when the user wants to run, prepare or adapt an OdoBot/WebArena/Canvas evaluation experiment, or write an experiment definition.
---

# Running an OdoBot experiment

An **experiment** is one experiment definition JSON POSTed to an OdoBot instance at `POST /api/evaluate?agent=<agent>`:
- The instance runs the definition's tasks **one after another**. The request returns once the last task has finished.
- Every artifact goes to `execution_events/<experimentId>/`.
- One OdoBot instance runs **one experiment at a time**. To run experiments concurrently, use one instance per experiment. The [`odobot-instances`](../odobot-instances/SKILL.md) skill starts and manages those instances. This skill covers what to send them.

`example-experiment.json` (next to this file) is a working template. It holds one string-match task from each of four WebArena apps (shopping_admin, shopping, reddit, gitlab), run by the uncharted Qwen agent. Its app addresses are placeholders, which must be filled in for each environment (see below).

**Before any end-to-end run:**
- **Rebuild the image if the code changed:** `docker build -t aianta/odobot:latest -f docker-gradle/Dockerfile .`
- **Check in with the user** before submitting. Runs take minutes per task and use the user's environment and model server.

## The experiment definition

| Field | Required | Meaning |
|---|---|---|
| `odoSightPath` | yes | The OdoX extension, inside the OdoBot container: `./odo-sight.xpi`. |
| `webAppURL` | yes | The page the browser opens before each task, unless the task has a `startUrl`. It must be reachable **from the Firefox container**. |
| `guidanceServiceHost` | yes | Where OdoX (in Firefox) reaches OdoBot: `<OdoBot container IP>:7080`. Use the container port, not a host port. |
| `logUIHost`, `logUIUsername`, `logUIPassword` | yes | LogUI settings OdoX needs to start. Nothing is sent to LogUI during evaluation. |
| `tasks` | yes | The tasks, see below. |
| `firefoxDockerGridURL` | in docker, yes | `http://<Firefox container IP>:4444`. Without it OdoBot starts a local Firefox, which doesn't exist inside the container. |
| `experimentId` | no (`default`) | The output folder name. Use a **new id per run**. A task whose outputs already exist under that id is **skipped**, and the experiment summary counts every task file in the folder. |
| `targetHosts` | no | The origins whose network events OdoX captures, e.g. `["http://172.23.0.2", "http://172.23.0.6:8023"]`, or `["*"]` for all. List **every app** the tasks visit. Without it, OdoX captures only the host of each task's start page. |
| `timeout` | no (180000) | Milliseconds without a new agent instruction before a task fails with `Timeout!`. The timer resets on each instruction, so this isn't a total task limit. |
| `headless` | no (true) | Run Firefox without a display. Set `false` to watch the run over VNC. |
| `llm` | no | OpenAI-compatible client settings for both agents: `model`, `base_url`, `api_key`, sampling, `timeout_seconds`, `max_attempts`. For a model server on the docker host, use `http://host.docker.internal:<port>/v1`. See README "LLM settings". |
| `qwen` | no | Uncharted agent settings: `history_n`, `image_max`, `fold_size`, `coord`, `collapse_text`. |
| `evaluationDatasetPath` | no | The ground-truth dataset, relative to `canvas-evaluation-scripts`, e.g. `sample_generated_data/cascon-2026/tasks.json`. It turns on scoring and Elasticsearch telemetry. It is for **Canvas only**: the evaluation script can't score WebArena tasks, so leave it out for them. |
| `notes` | no | Copied into the experiment telemetry, e.g. the number of concurrent instances. |
| `pathSelectionMode` | no | Charted agent only. |

**Each entry in `tasks`** is `{ "<task field>": { ... } }`. The task field depends on the agent: `odoBotNL` for `agent=odoBotNL`, `uncharted` and `hybrid`, and `odoBot` for `agent=odoBot` (see README "Task Execution Modes"). Inside it:
- **`id`:** a UUID, which must parse. The WebArena tasks use `uuid5(NAMESPACE_URL, "webarena/<task_id>")`.
- **`_evalId`:** unique per task, used in file names (`|` becomes `-`). The convention is `<dataset task id>|OdoBotNL|<id>`.
- **`task`:** the instruction the agent gets. Put credentials first, e.g. `Use the username: admin and password: admin1234 to login to the Magento admin panel.\nTask: ...`. WebArena's accounts are in `browser_env/env_config.py` of the WebArena repo (`/home/aianta/shock_and_awe/WebArena` in WSL).
- **`startUrl`:** optional. The page this task starts on, instead of `webAppURL`. It lets one experiment cover several apps. Reachable from Firefox, like `webAppURL`.
- **`userLocation`:** used by the charted agent to find its starting point. It is not opened by the browser.

Agents (the `agent=` query parameter):
- **`uncharted`:** the Qwen screenshot agent.
- **`odoBotNL`:** charted, natural-language tasks.
- **`odoBot`:** charted, predefined tasks.
- **`hybrid`:** not implemented.

## Adjusting a definition to an environment

`example-experiment.json` only holds placeholders for the app addresses. Every address in a definition is seen **from inside the containers**, not from the host. For each environment:

1. **Put the apps where the containers can reach them.** The OdoBot and Firefox containers live on `odobot-net`. An app started by its own compose project sits on another network, so attach it with `docker network connect odobot-net <container>` (`odobot-instances up -EnvContainers ...` does this). Then read its address:
   ```powershell
   docker inspect <container> --format '{{(index .NetworkSettings.Networks "odobot-net").IPAddress}}'
   ```
2. **Replace the placeholders** with those addresses: `<SHOPPING_ADMIN_IP>`, `<SHOPPING_IP>`, `<REDDIT_IP>`, `<GITLAB_IP>`.
   - Put them in each task's `startUrl` and `userLocation`, in `targetHosts`, and in `webAppURL`.
   - Use the port each app serves on **inside its container**. That's 80 (no port) for shopping, shopping_admin and reddit, and `:8023` for gitlab, not the host-published 7770, 7780, 9999 and 8023.
   - Drop the tasks and `targetHosts` entries of apps the environment doesn't run.
3. **Check where each app redirects.** Magento (shopping, shopping_admin) and GitLab redirect to their configured base or external URL. It must be the same address the browser uses, or the browser ends up on an unreachable host or on another environment's app. Open the start page in the instance's VNC before a long run. To fix Magento, run `php bin/magento setup:store-config:set --base-url=http://<ip>/` (and `--base-url-secure`) inside the container, then flush the cache.
4. **Point the definition at its OdoBot instance.** Set `guidanceServiceHost` to `<OdoBot IP>:7080` and `firefoxDockerGridURL` to `http://<Firefox IP>:4444`.
   - The example's `172.23.0.4` and `172.23.0.3` match a single pair started by hand at those addresses.
   - `odobot-instances submit` fills both in for each instance.
5. **Point it at the model server and LogUI.**
   - **`llm.base_url`:** `host.docker.internal:<port>` reaches a server on the docker host. The example uses the local Qwen at `:8080`.
   - **`logUIHost`:** must be the host's address as seen from the containers. On this machine that's `172.26.128.1:8000`.
6. **Pick a new `experimentId`**, and record anything that affects the results (instance count, model, environment) in `notes`.
7. **Check the credentials.** Credentials match the stock WebArena images. Change them if an environment was set up with other accounts.
8. **Reset app state if needed.** The example tasks only read, but WebArena tasks that change state need a freshly started environment for each run.

## Running on one instance

Start an OdoBot + Firefox pair, either with `odobot-instances up -Count 1` or by hand with the flags in that skill's script. Then:

```powershell
# With odobot-instances: fills in guidanceServiceHost, firefoxDockerGridURL and the experimentId suffix
pwsh -File .claude/skills/odobot-instances/odobot-instances.ps1 -Action submit -Count 1 -Template my-experiment.json

# Or POST directly to the instance's API port (9001 for odobot-instances instance 1, 8076 for the single `odobot` container)
curl.exe -s -S -X POST "http://localhost:9001/api/evaluate?agent=uncharted" -H "Content-Type: application/json" --data-binary "@my-experiment.json" -w "\nHTTP %{http_code}\n"
```

The request blocks until the experiment finishes. From a Claude Code session, run it in the background and wait for it to finish rather than polling. Watch progress over the instance's VNC (`http://localhost:<7910+i>`, password `secret`, with `headless: false`) or with `docker logs -f odobot-<i>`.

`cascon-experiment.bat` is the Canvas pipeline. It resets the Canvas environment, rewrites addresses, scores and collects logs. Use it for Canvas experiments, not for WebArena definitions like the example.

## Running on several instances, and concurrent experiments

One instance runs one experiment, so:
- **Several experiments at once need one instance each.** Start them with `odobot-instances up -Count N`, or with `-Start` to add more next to running ones.
- **Each experiment needs its own environment**, or at least its own app state, and its own `experimentId`.

See the odobot-instances caveats: shared model server, mounts, resources, address planning.

**The same definition on N instances,** e.g. repeats of the example against N environments:

```powershell
$s = '.claude/skills/odobot-instances/odobot-instances.ps1'
pwsh -File $s -Action up -Count 2 -EnvContainers envA-shopping_admin-1,envB-shopping_admin-1
pwsh -File $s -Action submit -Count 2 -Template my-experiment.json -EnvContainers envA-shopping_admin-1,envB-shopping_admin-1
```

Instance i runs `<experimentId>-<i>`. `submit` sends all N requests at once and waits for all of them.

**Different definitions at once:** target each instance with `-Start <i> -Count 1`. Each `submit` blocks, so start them side by side, e.g. as background shell commands or separate terminals:

```powershell
pwsh -File $s -Action up -Count 2
pwsh -File $s -Action submit -Start 1 -Count 1 -Template experiment-a.json   # runs as experiment-a-1 on instance 1
pwsh -File $s -Action submit -Start 2 -Count 1 -Template experiment-b.json   # runs as experiment-b-2 on instance 2
```

**Multi-app definitions such as the example:** `submit` rewrites only the origin of `webAppURL`, but the example has one origin per app. So run `submit ... -PrepareOnly` and fill in each generated `instance-<i>.json` with that environment's app addresses (steps 1–3 above). Then POST each file to its instance's API port (`http://localhost:<9000+i>`) at the same time, for example:

```powershell
$jobs = 1..2 | ForEach-Object {
    Start-Job -ArgumentList $_, "$runDir\instance-$_.json" -ScriptBlock {
        param($i, $file)
        curl.exe -s -S -X POST "http://localhost:$(9000 + $i)/api/evaluate?agent=uncharted" -H "Content-Type: application/json" --data-binary "@$file" -w "HTTP %{http_code}"
    }
}
$jobs | Wait-Job | Receive-Job
```

Run the experiments of a comparison at the **same concurrency level**. Concurrent agents share the model server, which inflates execution time.

## Outputs

Everything lands under `execution_events/<experimentId>/` (the `execution_events` mount):
- **`<evalId>.json`:** the raw OdoX event log of a task, which is what gets scored.
- **`<evalId>-tokens.json`:** the task's token usage, plus `timing` (`setupMs`, `executionMs` split into `inferenceMs` and `otherExecutionMs`, `artifactsMs`, `scoringMs`, `totalMs`) and `outcome` (`completed` or `failed: <reason>`).
- **`results/<experimentId>-tokens.json`:** the experiment summary. It holds token stats and a `timing` section with per-phase stats, `inferenceShare`, slowest and fastest task, `byOutcome`, `wallClockMs` and `skippedTasks`.
- **Uncharted runs:** also `<evalId>-qwen38-trajectory.jsonl` and `<evalId>-qwen38-messages-step-<n>.json`. A task's answer is recorded in its event log.
- **With `evaluationDatasetPath`:** also `results/<id>-result.json` per task, `results/<experimentId>-results.json`, and Elasticsearch telemetry (`task_instance_results`, `experiment_results`).

**Response codes:**
- **`200`:** the experiment finished. With a dataset, the body is the experiment results. Failed or timed-out tasks still give 200; check each task's `outcome`.
- **`400`:** the definition was rejected before anything ran, e.g. an invalid `llm` or `qwen` setting, `targetHosts` that isn't an array, or a missing required field.
- **`500`:** the experiment-level evaluation failed.

## Troubleshooting

- **A task is skipped immediately ("Output artifacts ... already exist").** Its outputs are already under this `experimentId`. Use a new id, or delete that folder.
- **The task fails at setup with `Navigation to "moz-extension://..." is not allowed`.** The Firefox container lacks `MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1`.
- **The browser sits on an error page or another environment's app.** The start URL isn't reachable from Firefox, or the app redirected to its base URL (see step 3).
- **`call_llm failed attempt n/5` in the log.** The model server is overloaded or unreachable. Check `llm.base_url` from inside the container.
- **The task fails with `Timeout!`.** The agent produced no instruction for `timeout` ms. Check the model server and the OdoBot log.
