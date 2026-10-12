# Concurrent agent runs: design considerations

Analysis of what still prevents running charted and uncharted tasks concurrently in one OdoBot process, i.e. several
`POST /api/evaluate` requests to the same instance at once. Tasks within a single request already run one after another
(`ExplorerVerticle.evaluateHandler` chains them with `f.compose`).

**Status (2026-10-09):** concurrent runs are not yet safe to start, for either agent. No remaining shared state gives one
task another task's data, but every task's sockets and agent logic share one event-loop thread, and that thread sleeps
before each instruction any task sends.

Already addressed on branch `multiple-agent-support`:
- **Socket ↔ task association:** `ClientRegistry`. OdoX uses its extension instance id (`dynamicAddonId`) as its
  `clientId` and sends it in the socket URL. Each task waits for its own client and releases only that client.
- **Per-task LLM settings and token usage:** `LlmCallScope`, owned by each task's `RequestManager`. It is given to:
  - the Qwen model client;
  - a scoped copy of Snippet2XML;
  - a task planner registered at a per-task event-bus address, whose similar-task embedding comes back through
    `SqliteVectorService.topKWithUsage`.

  The static `LlmClientConfig.active` is gone. `TokenUsageRecord.active` only collects calls made outside a task.

Evidence below comes from the code and from the Canvas smoke run in
`execution_events/smoketest-odobot-cascon-scoped-1/logs/odobot.log`.

## Blockers for both agents

### 1. One shared event loop that sleeps before every instruction

- `GuidanceVerticle` is deployed as a single instance (`MainVerticle.java:91-97`). Every OdoX socket of every task, and
  the agent logic that runs on those messages, share one event-loop thread. In the smoke run all three sockets of both
  tasks were bound on `vert.x-eventloop-thread-7`.
- `GuidanceConnectionManager.sendExecutionInstruction` calls `Thread.sleep(followUp ? 1000 : 3000)` on that thread
  before every instruction (line 270). `showNavigationOptions` sleeps 1 s (line 161).
- Uncharted steps go through the same method (`RequestManager.java:404`), so each uncharted step sleeps 3 s.
- In the smoke run thread-7 was reported blocked 17 times, for up to 2.9 s, each time right after
  "Added EXECUTION_RESULT promise". That is these sleeps.

**Consequence:** with N tasks, each instruction of any task freezes every other task's events, execution results and
timers. That includes the `UnchartedStepObserver` settle timing, the DOM-settle limit and the task timeouts. Expect
execution time to grow with N, skewed observations, and spurious `Timeout!` failures.

### 2. Blocking jobs queue one at a time on the shared context

- `Snippet2XMLServiceImpl` makes its LLM calls synchronously inside `vertx.executeBlocking(handler)`, which runs one job
  at a time per context. `ChartedAgent` calls it from the shared guidance context, so every charted task's Snippet2XML
  calls (element pick, value pick, HTML to XML) run one after another.
- `Qwen38Agent` uses `context.executeBlocking` on the same context (lines 168 and 320) for screenshot processing and
  artifact writes. These are short jobs, but in a mixed run an uncharted step can wait behind a charted task's LLM call.

## Charted only

### 3. Task-planner LLM calls block event loops

- Most `TaskPlannerServiceImpl` strategy calls run the chat completion directly on the calling event loop: task rewrite,
  similar-task pick, API-call pick, text input, radio, select and checkbox. Only `selectPath` uses `executeBlocking`.
- Before the scoping change these calls blocked the `TaskPlannerVerticle`'s own event loop. Each task's planner is now
  registered from the `EvaluateTask` thread, so it gets a new context, placed round-robin across the event-loop threads.
- In the smoke run, thread-1 was blocked for 2.7–3.7 s during "Performing Task Query Construction".

**Consequence:** with concurrent tasks, a planner can land on the guidance thread or on another task's thread, and freeze
it for each LLM call.

### 4. Path construction runs on the shared event loop

Neo4j queries, localization and path ranking run on the guidance thread (353 charted log lines on thread-7 in the smoke
run). Concurrent read transactions are safe, but this CPU work is serialized across tasks.

## Checked and not a problem

- **Navigation model state:**
  - The `Localizer` indexes are only rebuilt in its constructor (`refreshIndices`).
  - `NavPathsConstructor.globalParameterMap` and the static `NavPath.globalParameterMap` are reassigned on every task,
    but always to the same map, loaded once from Neo4j.
  - The static counters and LRU state of `DijkstraCache` are only reachable from the deprecated `ClickEvent.terms()` and
    `DataEntry.terms()`, which have no callers.
- **Databases:**
  - The charted task path only reads SQLite, through a `JDBCPool` of 16 connections. The `mergeEventNodeMappings` writes
    belong to model construction (`CollapsingTraversal`, `PostLocationEffectMerger`).
  - Neo4j use during tasks is read-only transactions.
- **Per-task objects:** `OdoClient`, `RequestManager`, the connection managers and `OnlineEventProcessor` are all per task.
- **Remaining static fields:** `MainVerticle.MODEL_OVERRIDE`, the services' `model` strings, `GuidanceVerticle._vertx` and
  the formatters are effectively read-only.

## Harness and environment

5. **No way to launch it yet.**
   - A Selenium standalone grid runs one session, so each concurrent request needs its own `firefoxDockerGridURL`.
   - `cascon-experiment.bat` drives exactly one `odobot`/`canvas`/`selenium-firefox` set and resets Canvas for each
     instance.
   - Nothing yet starts N grids for one OdoBot and sends N requests to it.
6. **Shared Canvas state.** The dataset assumes a fresh environment per instance. Concurrent charted tasks on one Canvas
   can collide on shared objects such as groups or enrolments, which could change both execution and scoring.
7. **Model throughput.** Charted tasks share one OpenAI key, and its rate limits allow only 3 attempts per call
   (`max_attempts`). Uncharted tasks share the local Qwen server. Both inflate timing (see the odobot-instances skill
   caveats).

## Minor

- Raw events are written to disk synchronously on the guidance event loop (the `BufferedWriter` in
  `OnlineEventProcessor`). With N tasks, all their events go through that one thread.
- In non-strict mode (`strictClientIds: false`), a browser that reconnects after its task released it would leave an
  unexpected client behind that is never removed. This didn't happen in the smoke run.

## Fix direction, in priority order

1. Replace the `Thread.sleep` calls in `GuidanceConnectionManager` with Vert.x timers.
2. Give each client its own context. Either deploy the guidance verticle as several instances, or move each client's
   message handling and agent logic onto a context of its own.
3. Allow Snippet2XML's and the Qwen agent's blocking jobs to run in parallel (`executeBlocking` with `ordered=false`), or
   run them on a per-task context.
4. Move the task planner's synchronous LLM calls into `executeBlocking`.
5. Verify with a two-request uncharted run on one OdoBot, before trying charted. That needs two Firefox grids and a
   separate experiment id per request.
