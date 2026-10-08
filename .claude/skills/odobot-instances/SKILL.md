---
name: odobot-instances
description: Launch N independent OdoBot + Firefox container pairs on odobot-net and submit one evaluate request to each at the same time, e.g. to run the uncharted agent against several WebArena environments in parallel. Use when the user wants concurrent OdoBot task executions, multiple OdoBot instances, or parallel evaluation runs; also covers status, teardown and the caveats of running them concurrently.
---

# Multiple OdoBot instances

One OdoBot process runs **one task at a time**. To run tasks concurrently, start one OdoBot container **and** one Firefox grid per concurrent execution, and send each pair its own evaluate request. `odobot-instances.ps1` (next to this file) does this.

## Why not one OdoBot with concurrent requests

The endpoint accepts concurrent requests, but the tasks would break each other:

- **Tasks within a request run one after another.** `ExplorerVerticle.evaluateHandler` chains them with `f.compose`.
- **Concurrent requests each start an `EvaluateTask` thread right away**, and they share process-wide state:
  - **Each task could grab the other's browser.** `WebSocketConnection.clientMap` is a static map of OdoX connections. `EvaluateTask.setupEnvironment()` assumes it holds one entry and takes the first one.
  - **The first task to finish cuts off the other.** `EvaluateTask.cleanUp()` calls `clientMap.clear()`.
  - **Token counts and timing go to the wrong task.** `TokenUsageRecord.active` is static and is overwritten by each `RequestManager.startTask`. That corrupts each `-tokens.json`, including its `timing`.
  - **LLM settings get crossed.** `LlmClientConfig.active` is static, so one experiment's `llm` settings can apply to the other's calls.
- **The Firefox grid has one session.** `selenium/standalone-firefox` runs one session at a time by default.

## Usage

Run from the odo-bot project root. Build the image first if the code changed (`docker build -t aianta/odobot:latest -f docker-gradle/Dockerfile .`). Per the user's standing preference, **check in with the user before submitting e2e runs**.

```powershell
$s = '.claude/skills/odobot-instances/odobot-instances.ps1'

# 1. Start N pairs. -EnvContainers attaches the web app containers (one per instance) to odobot-net.
pwsh -File $s -Action up -Count 2 -EnvContainers env1-shopping_admin-1,env2-shopping_admin-1

# 2. Optional: write the per-instance task files and check them.
pwsh -File $s -Action submit -Count 2 -Template time-logging-test.json -EnvContainers env1-shopping_admin-1,env2-shopping_admin-1 -PrepareOnly

# 3. Submit all instances concurrently and wait for every experiment to finish.
pwsh -File $s -Action submit -Count 2 -Template time-logging-test.json -EnvContainers env1-shopping_admin-1,env2-shopping_admin-1

# Status and teardown
pwsh -File $s -Action status -Count 2
pwsh -File $s -Action down -Count 2
```

Options:
- **`-Start`:** the first instance an action covers (default 1). An action covers instances `Start` to `Start+Count-1`. For example, `-Start 3 -Count 2` acts on instances 3 and 4. Use it to send different experiments to different instances. The `run-experiment` skill covers running experiments, including several different ones at once.
- **`-Image`:** the OdoBot image, default `aianta/odobot:latest`.
- **`-Agent`:** the evaluate `agent` parameter, default `uncharted`.
- **`-ExperimentId`:** the id prefix, default the template's.
- **`-WebAppUrls url1,url2`:** use instead of `-EnvContainers` when the environments are reachable by URL.
- **`-RunDir`:** where task files, responses and logs go, default `%TEMP%\odobot-instances\<id>-<time>`.

**What `submit` changes in each instance's copy of the template:**
- **`experimentId`:** becomes `<prefix>-<i>`.
- **`guidanceServiceHost`:** set to `<odobot-i IP>:7080`.
- **`firefoxDockerGridURL`:** set to `http://<selenium-firefox-i IP>:4444`.
- **`webAppURL`:** set to the instance's environment. The matching origin in each task's `userLocation` and in `targetHosts` changes to match.

Task text, including credentials, is not changed.

Only the origin of `webAppURL` is rewritten (in `webAppURL`, `userLocation`, `startUrl` and `targetHosts`). A template whose tasks start on several applications through per-task `startUrl` (e.g. `resource-use-sampling.json`) has one origin per application. For those, write each instance's file by hand, or run `-PrepareOnly` and edit the generated files before submitting.

**Instance i layout:**

| | container | address on odobot-net | host ports |
|---|---|---|---|
| OdoBot | `odobot-i` | `<prefix>.2.i` | API `9000+i`, guidance `7100+i` |
| Firefox | `selenium-firefox-i` | `<prefix>.3.i` | grid `4500+i`, VNC `7910+i` (password `secret`) |

These names and ports don't clash with `cascon-experiment.bat`, which uses `odobot`/`selenium-firefox` on 8076/7080/4444/7900. They also avoid the local model server (8080) and LogUI (8000). Note that `cascon-experiment.bat` does `docker rm -f odobot selenium-firefox canvas` and attaches to `odobot-net`. It leaves numbered instances alone, but its Canvas container takes the next free address on the network.

## Attaching environments

The browser runs inside `selenium-firefox-i`, so each environment's web app must be reachable on `odobot-net`. Environments started by their own compose project (e.g. `env1`, `env2`) live on their own networks (`env1_default`, `env2_default`) and must be attached:

- **How:** `up -EnvContainers env1-shopping_admin-1,env2-shopping_admin-1` runs `docker network connect odobot-net <container>` for each container that isn't attached yet, then prints its address on `odobot-net`. For an environment brought up later, run the same command by hand.
- **What changes:** the container stays on its own compose network and gets an **extra** address on `odobot-net`. Its host ports (e.g. `7780`) and its compose networking are unchanged.
- **Pairing:** instance i targets the i-th entry of `-EnvContainers`. Give exactly one container per instance, in the order you want them paired.
- **URLs:** `submit -EnvContainers ...` builds instance i's `webAppURL` as `http://<container i's odobot-net IP>` plus the path of the template's `webAppURL` (e.g. `/admin`). It then rewrites the matching origin in `userLocation` and `targetHosts`.
- **Order:** `submit` doesn't attach anything. If a container isn't on `odobot-net`, it stops and asks you to run `up` with `-EnvContainers` first.
- **Check:** `docker inspect <container> --format '{{(index .NetworkSettings.Networks "odobot-net").IPAddress}}'`. Use `-PrepareOnly` to see the generated URLs, then watch the first page load in the instance's VNC. Magento may redirect to its configured base URL (see caveat 2).
- **Undo:** `down` leaves environments attached. Detach them with `docker network disconnect odobot-net <container>`.

## Caveats

1. **Give each instance its own environment.** WebArena and Canvas tasks change application state: carts, orders, groups, comments. Two agents in one environment interfere, and scoring breaks. `submit` warns when every instance targets the same environment.
2. **Environments must be reachable from `odobot-net`.**
   - The browser runs in `selenium-firefox-i`, so `webAppURL` must resolve from inside that container. See [Attaching environments](#attaching-environments).
   - Host ports like `localhost:7780` do not work from inside the containers.
   - **Magento may redirect to its configured base URL.** WebArena's Magento sends the browser to its configured base URL. If that isn't the address the grid uses, the browser leaves for an unreachable host or for another environment. Check with `-PrepareOnly`, then open the instance's VNC, or set the base URL in that environment (`php bin/magento setup:store-config:set --base-url=...`).
3. **Use a unique `experimentId` per instance.** All instances share the `execution_events` mount:
   - Two instances on one id write into one folder at the same time.
   - The skip check (`<instance id>.json` already present) silently skips tasks.
   - The experiment summary counts every `*-tokens.json` in the folder.
   
   `submit` adds the `-<i>` suffix, and warns when a folder already exists, because its tasks are skipped and earlier runs get folded into the summary.
4. **A shared model server skews timing.** Every uncharted instance calls the same Qwen server (`host.docker.internal:8080`):
   - Concurrent agents queue behind each other, so `executionMs`, `totalMs` and telemetry `Duration` grow with the number of instances.
   - More `call_llm failed attempt` retries are likely, and their time is counted as execution.
   - Record the concurrency level with the results (e.g. in `notes`), and don't compare timings across runs with different instance counts.
   - Queueing at the model server shows up in each task's `timing.inferenceMs`; `otherExecutionMs` (browser, environment and harness work) should stay roughly flat. Compare those two, or the summary's `inferenceShare`, to tell model-server contention from a starved browser or environment.
   
   Token counts are not affected.
5. **Mounts are shared.**
   - `config`, `libs` and `db` are mounted into every OdoBot.
   - Uncharted runs don't use the navigation-model databases.
   - Charted runs read and write SQLite/graph stores under `db`, and concurrent writers may lock or corrupt them. Prefer uncharted runs, or give charted instances separate copies.
   - LLM settings come from each request's `llm` object, so instances can use different models. Settings from `config/*.yaml` are shared.
6. **Telemetry is shared too.** Every instance reports to the same Elasticsearch indices. Separate them by `ExperimentId`, which has the `-<i>` suffix.
7. **Watch resources.**
   - Each pair is a JVM plus a Firefox with a 2 GB `/dev/shm`. Watch host memory and CPU, because a starved browser makes the agent slower and shows up in `executionMs`.
   - Each request runs its tasks in order inside its own instance, so a slow environment only holds up that instance.
8. **Firefox needs two settings.** `MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1` lets WebDriver open OdoX's moz-extension pages. `SE_NODE_SESSION_TIMEOUT=86400` stops the grid from closing Firefox 5 minutes into a task. The script sets both. Keep them if you start containers by hand.
9. **Port 7080 is not a health check.** The guidance port doesn't answer plain HTTP, so curl to it fails even on a healthy instance. The script checks the API port (any HTTP status means OdoBot is up) and the grid's `/status` instead.
10. **Clean up afterwards.** `down` removes `odobot-i` and `selenium-firefox-i`. It doesn't detach environments that `up` attached to `odobot-net`. Detach them with `docker network disconnect odobot-net <container>` if needed.

## After a run

- **Each instance's results:** `execution_events/<prefix>-<i>/`. That folder holds the `-tokens.json` with `timing` per task, and `results/<prefix>-<i>-tokens.json` for the experiment summary.
- **HTTP responses and full OdoBot logs:** in the run directory. `submit` prints a table with each instance's HTTP status and summary path.
