# Design: a task verticle per task

Exploration of refactoring the server so that everything a task needs at runtime is merged into one verticle, deployed
when the server receives a new task. Companion to
[concurrent-agent-design-consideratons.md](concurrent-agent-design-consideratons.md), whose blockers this design removes.

**Status (2026-10-12):** steps 1–6 implemented on `multiple-agent-support` and smoke-tested, see
[Smoke test results](#smoke-test-results). See
[Implementation notes](#implementation-notes) for where the code differs from this design.

OdoX-initiated requests (`PATHS_REQUEST`, `EXECUTION_REQUEST`) are already ignored (`ControlConnectionManager`), so every
task starts on the server. That is what lets a verticle per task own the task from start to finish.

## Overall shape

```
ExperimentOrchestrator (today: ExplorerVerticle.evaluateHandler)
  validates the request, skips finished tasks, resolves each task's environment,
  then deploys a TaskVerticle per task
        │  deployVerticle(new TaskVerticle(task, environment, platform))
        ▼
TaskVerticle  ── one per running task, with its own event-loop context
  wss listener ── OdoX sockets (Control / Event / Guidance)
  harness      ── RequestManager, connection managers, OnlineEventProcessor, step observer
  agent        ── ChartedAgent or Qwen38Agent
  LLM clients  ── LlmCallScope, Qwen client, scoped TaskPlanner / Snippet2XML
  browser      ── Selenium driver, OdoX setup and cleanup (on worker threads)
  artifacts    ── events, tokens and timing, histories, trajectories, scoring
        │  replies with the task's outcome, then is undeployed
        ▼
Platform (shared for the whole process; read-only or thread-safe)
  embedded Neo4j GraphDB, Neo4JUtils, Localizer, NavPathsConstructor,
  planner and Snippet2XML prototypes, OpenAI connection pools, Stanford NLP,
  config
        ▲  proxies over the event bus
  service verticles: SqliteService (JDBC pool), SqliteVectorService, TelemetryService
```

The goal is that everything mutable for a task lives on that task's context. Then concurrent tasks share only the
platform, which is read-only during tasks or already thread-safe.

## Routing sockets to their verticle

This is the main design decision. Vert.x can let several verticles listen on the same port, but it hands out connections
round-robin, so it can't route by `clientId`. That leaves two options.

### A. A listener per task (recommended)

Each task verticle runs its own wss server. OdoX is pointed at that server when the browser is set up, so every socket
it opens lands on the task's context by construction. Nothing has to look up which task a socket belongs to.

**Opening the listener.** In `start()`, before the browser is set up:
- Build the server with the options `GuidanceVerticle.getServerOptions()` uses today: TLS from the same JKS
  (`jksPath`, alias `odobot-server`), and 10 MB websocket frame and message limits. Move them into a shared
  `OdoXServerOptions.from(config)` so both places agree.
- Listen on port 0 and read the port the OS picked from `HttpServer.actualPort()`. The OS never hands the same free
  port to two listeners, so concurrent tasks need no coordination.
- The server has only the websocket handler. No HTTP routes are needed now that OdoX no longer calls `/api/targetNodes`.

**Pointing OdoX at it.** `setupOdoX` types `guidanceServiceHost` from the evaluate request into the options page. The
task verticle keeps the host part and replaces the port with its own, so the request format doesn't change. In the
docker deployment, the host part must be OdoBot's name or address on `odobot-net`, e.g. `odobot:7080`, not a host IP
such as the `172.26.128.1` in the older smoke-test requests, which would only reach published ports. The profile already sets `acceptUntrustedCertificates`, which should cover the self-signed cert on any
port. A smoke test should confirm that before relying on it.

**Accepting sockets.** The verticle knows its one `clientId` (`dynamicAddonId`) before the browser exists, so:
- a socket whose URL carries that `clientId` and a valid `source` is bound straight to the task's `OdoClient`;
- a socket with a different `clientId` is refused with a log line. This is today's strict mode, always on;
- a socket with no `clientId` in its URL (an older OdoX) is held until its first message names one, then bound or
  closed, as `WebSocketConnection` does today;
- a second socket for a source that is already bound replaces the old one, which covers OdoX reconnecting.

The `ready` promise that `ClientRegistry.expect` returns today becomes a field of the verticle. It completes once the
Control and Event sockets are bound, and a timer fails it if they don't arrive within the setup timeout.

**Ping/pong and closing.** Keepalive stays per socket, as now. If a socket closes mid-task, the harness treats it the
way it treats a drop today. If the task is over, the close is expected and ignored. Undeploying the verticle closes the
server and with it any sockets still open.

**What this removes.** `ClientRegistry`, the `strictClientIds` setting, the `clientMap` concept and the shared websocket
handler in `GuidanceVerticle`. No routing table, no relay hop, and no copies of the 10 MB DOM frames.

**Costs:**
- It relies on Firefox and OdoBot sharing a docker network, where any container port is reachable without publishing.
  That is the expected deployment. A Firefox outside docker would need option B.
- Each task pays for a TLS server start, a few milliseconds and small next to browser setup.

### B. Keep the shared listener and relay

- `GuidanceVerticle` accepts the socket, reads `clientId` from the URL as it does today, and relays frames over the event
  bus to `odox.<clientId>.<source>`.
- One public port, and it works anywhere. But each frame gets an extra hop and is copied, and the gateway is still one
  shared loop, though it would only be doing cheap relay work.

Use A. B is only worth building if a deployment without a shared docker network ever matters.

## What goes into the task verticle

These are taken from what a task touches today:

| Responsibility | Moves from | Notes |
|---|---|---|
| Socket endpoint, accepting the one expected `clientId`, ping/pong | `GuidanceVerticle` websocket handler, `WebSocketConnection`, `ClientRegistry` | Replaces the global registry with a check in each verticle |
| OdoX client and the three connection managers | `OdoClient`, `Control`/`Event`/`GuidanceConnectionManager` | The control manager is nearly a no-op now |
| Harness: task start, executing instructions, timeout, completion promise, timing, token usage | `RequestManager` | Stays the harness. The `IAgent` contract is unchanged, which keeps the agent/harness split rules |
| Event processing and observation | `OnlineEventProcessor`, raw event streaming, `UnchartedStepObserver`, DOM-settle delays | Raw event file writes move to the worker pool (or `AsyncFile`) |
| The agent | `EvaluateTask.buildAgent` | Built in `start()` from the task definition |
| Per-task LLM clients | `LlmCallScope`, `OpenAIQwenModelClient`, scoped `TaskPlannerServiceImpl` / `Snippet2XMLServiceImpl` | Plain objects inside the verticle. The per-task event-bus binding added for the planner goes away |
| Browser lifecycle | `EvaluateTask.setupEnvironment`, `setupOdoX`, `buildProfile`, `cleanUp` | Selenium blocks, so it runs on worker threads. `stop()` must always quit the driver |
| Instruction pacing | `Thread.sleep(1000/3000)` in `GuidanceConnectionManager` | Becomes `vertx.setTimer` with the same delays (done in step 1). See [Instruction pacing](#instruction-pacing) for the change in behaviour |
| Task artifacts | event log, `-tokens.json`, connection histories, Qwen trajectory and messages | Unchanged formats |
| Task evaluation and per-task telemetry | `EvaluateTask`'s completion handler (python subprocess, `TaskInstanceResults`) | Stays in the verticle, because future evaluators need the live browser. See [Task evaluation](#task-evaluation) |

### Stays in the orchestrator, per experiment

- request validation (`llm`, `qwen`, and each task's environment);
- experiment folders and the skip check;
- running tasks in order, or N at a time;
- resolving each task's environment from the task and the request, see [Environments](#environments);
- experiment-level results and telemetry, built from what the tasks report. See
  [Experiment-level telemetry](#experiment-level-telemetry).

## Environments

Every task, whether WebArena or Canvas, charted or uncharted, runs in an environment of its own. **OdoBot does not
manage environments.** An external process, script or manager makes sure the right environments are up, and in the
right state, before it submits the tasks. OdoBot doesn't provision, lease, reset or tear them down, and it doesn't
check that two concurrent tasks use different environments. That is the manager's job too.

OdoBot's only job is that **every environment detail can be given per task**. Then the manager can point each task at
its own environment in the evaluate request.

### Task-level environment fields

A task carries an optional `environment` object:

```json
{
  "id": "7aef98f1-2540-5d01-bf97-1dae57cf87b1",
  "task": "What is the top-1 best-selling product in 2022",
  "environment": {
    "firefoxDockerGridURL": "http://firefox-3:4444",
    "startUrl": "http://shopping-3:7780/admin",
    "targetHosts": ["http://shopping-3:7780"],
    "sites": { "shopping": "http://shopping-3:7780" }
  }
}
```

| Field | What it's for | Experiment-level field it overrides |
|---|---|---|
| `firefoxDockerGridURL` | The Selenium grid of the task's Firefox. Missing at both levels means a local `FirefoxDriver` | `firefoxDockerGridURL` |
| `startUrl` | The page the browser opens. `EvaluateTask.startUrl()` already reads a top-level task `startUrl`; it moves here | `webAppURL` |
| `targetHosts` | The hosts OdoX records network events for. Missing means the host of `startUrl`, as today | `targetHosts` |
| `sites` | Named base URLs of the environment's apps, for evaluators that query a backend | none yet |
| `logUIHost`, `logUIUsername`, `logUIPassword` | Values OdoX's options page requires, though nothing is sent to LogUI during evaluation | the same three |

**`startUrl` replaces the task's `userLocation`.** The two describe the same thing, the page the task starts on:
- the charted agent plans from the user location in each OdoX observation, i.e. the page the browser is really on. The
  task's `userLocation` is only its fallback when an observation carries none (`ChartedAgent.plan`);
- the `Localizer` uses only the path of a URL (`resolveStartingNode`, `findNodeByLocationChange`) or the method and
  path of a request (`findNodeByNetworkEvent`), never the host;
- the only reason both exist is that task files hold host-side addresses in `userLocation` (e.g.
  `http://localhost:8088/login/canvas`) that the browser container can't reach, so `startUrl()` deliberately ignores it.

So the charted agent's fallback becomes the resolved `startUrl`, and the task-level `userLocation` is dropped. Task
files that still carry it are accepted, but the field is ignored and a warning logged, so an old task never navigates
to a host the browser can't reach. The `userLocation` that OdoX sends with observations and screenshots is a different
thing and is unchanged. Updating the task generators to write `environment.startUrl` is out of scope.

### Resolving them

The orchestrator resolves each task's environment once, before deploying the task's verticle:

```java
record TaskEnvironment(String gridUrl, String startUrl, List<String> targetHosts, Map<String, String> sites,
                       LogUiSettings logUi){
    /** Each field from the task's environment, else from the experiment request, else its default. */
    static TaskEnvironment resolve(JsonObject task, JsonObject experimentRequest){ ... }
}
```

- **Task first, then experiment, then default.** Existing experiment requests keep working unchanged, and a request
  can mix: experiment-wide LogUI settings, per-task grids and URLs.
- **The task verticle reads only `TaskEnvironment`**, never the experiment-level fields. So whether those stay or are
  removed later only touches `resolve`.
- **Validation** happens in the orchestrator with the rest of the request: each task must end up with a `startUrl`,
  and `targetHosts` must be an array at either level. A bad task fails the request with a 400 before anything runs.
- **The resolved environment is saved** in the task's tokens file, so the artifacts record where each task ran.

## Shared platform services

Everything that is not per task or per experiment is a platform service: built once per process, shared by all task
verticles, and either read-only during tasks or safe to call from several threads.

### Two ways to reach a service

A task verticle reaches a platform service in one of two ways.

1. **A direct handle, called on the task's worker pool.** Used when the service is a thread-safe library object and its
   calls are synchronous: Neo4j, the navigation objects, the OpenAI clients, the NLP pipelines. The verticle calls it
   inside `executeBlocking`, so the work runs on the task's ordered queue in the shared `task-pool`, and the result
   comes back on the task's event loop.
2. **An event-bus service proxy.** Used when the service owns something that must be used from one place, such as a
   single JDBC connection, or is already asynchronous. The verticle builds its proxy from its own `vertx`, so replies
   come back on the task's context. These keep their `@ProxyGen` interfaces and addresses.

The rule of thumb: thread-safe and synchronous gets a direct handle; owns a single resource gets a proxy. Neither path
lets a task block an event loop, its own or a service's.

### The `Platform` handle

Today the task path reads statics: `LogPreprocessor.graphDB`, `.neo4j`, `.localizer`, `.pathsConstructor`,
`ExplorerVerticle.sqliteService`, `TaskPlannerVerticle.service`, `Snippet2XMLVerticle.snippet2XML` and
`TelemetryVerticle.telemetryService`. They become fields of one immutable `Platform` object, passed to the orchestrator
and from it to each task verticle's constructor:

```java
record Platform(
    GraphDB graphDb, Neo4JUtils neo4j,                 // direct, thread-safe
    Localizer localizer, NavPathsConstructor paths,    // direct, read-only
    TaskPlannerServiceImpl plannerPrototype,           // copied per task with withScope(...)
    Snippet2XMLServiceImpl snippet2XmlPrototype,       // copied per task with withScope(...)
    JsonObject config                                  // read-only copy
){}
```

Proxies are not in the record. Each task verticle builds its own from its own `vertx` and the address constants.

`MainVerticle` deploys its verticles in parallel today, and each publishes its statics whenever it finishes starting.
To build `Platform`, the verticles it depends on (`LogPreprocessor`, `ModelConstruction` for the vector service,
`TaskPlanner`, `Snippet2XML`, `Telemetry`) need to be chained: deploy them, wait for all of them, build `Platform`, then
deploy `ExplorerVerticle` with it. That also stops an evaluate request from arriving before the services are up.

### Service by service

| Service | Today | In the task verticle | Path | Notes |
|---|---|---|---|---|
| Embedded Neo4j (`GraphDB`, `Neo4JUtils`) | statics on `LogPreprocessor` | `platform.graphDb()`, `platform.neo4j()` | direct, worker | Transactions are bound to a thread, so each one is opened, used and closed inside a single `executeBlocking` job |
| `Localizer`, `NavPathsConstructor` | statics on `LogPreprocessor` | `platform.localizer()`, `platform.paths()` | direct, worker | Read-only. Localizing and `constructV3` share the job that holds the transaction |
| `SqliteService` (JDBC pool) | proxy at `sqlite-service`, kept as a static on `ExplorerVerticle` | the task's own proxy | event bus | Backed by an async `JDBCPool`, so already fine with concurrent callers |
| `SqliteVectorService` | proxy at `sqlite-vector-service`, registered by `ModelConstructionVerticle` | the planner's proxy | event bus | One JDBC connection, so it stays behind a proxy. Needs a fix, below |
| Task planner | event-bus binding per task at `task-planner-service.<clientId>` | `plannerPrototype.withScope(scope, context)` | plain object | The per-task binding goes away. Synchronous LLM calls run in `executeBlocking` |
| Snippet2XML | static impl, copied with `withScope` | `snippet2XmlPrototype.withScope(scope, context)` | plain object | Already wraps its LLM calls in `executeBlocking` |
| OpenAI clients | static map in `AbstractOpenAIStrategy` | unchanged, reached through the planner, Snippet2XML and the Qwen client | direct, worker | OkHttp clients are thread-safe. Check their connection limits against `maxConcurrentTasks` |
| Stanford NLP pipelines | loaded once at startup | unchanged | direct, worker | Only matters if a task-path call uses them |
| `TelemetryService` | static impl on `TelemetryVerticle`, called directly | the task's own proxy at `telemetry-service` | event bus | `client.index` blocks. Needs a fix, below |

**Passing the task's context to the scoped services.** `TaskPlannerServiceImpl` and `Snippet2XMLServiceImpl` keep the
`vertx` of the verticle that built them and call `vertx.executeBlocking`. Vert.x 4 runs that on whichever context is
current, which inside a task verticle is the task's, so it already works. Passing the task's `Context` into `withScope`
and calling `context.executeBlocking` makes that explicit, so it no longer depends on the caller. Their LLM jobs should
be unordered (`ordered = false`), or a long completion would hold up the task's other blocking jobs, such as raw event
writes.

**Fix: the vector service's embedding call.** `topKWithUsage` makes its OpenAI embedding call synchronously, on the
event loop of `ModelConstructionVerticle`, where the proxy delivers requests. With concurrent charted tasks, every
similar-task lookup queues on that one loop, and the loop is blocked while it waits on the API. The service should make
the embedding call in an unordered `executeBlocking`, so lookups overlap, then run the vector query in an ordered one,
so the single connection is still used by one job at a time. Deploying it in a verticle of its own with a small worker
pool keeps it out of model construction's way too.

**Fix: telemetry.** `TelemetryServiceImpl.reportTaskResults` calls the Elasticsearch client's blocking `index`. Today
that runs on the `EvaluateTask` thread. In a task verticle, it would run on the task's event loop. The impl should wrap
`index` in `executeBlocking`, and the task should call it through the proxy. Reporting is fire-and-forget: a failure is
logged and never fails the task.

### Communication with the orchestrator

The orchestrator and the task verticles talk directly within the JVM. The event bus is only needed between a task and
a service verticle.

- **Orchestrator to task, at deploy.** Constructor arguments: the task JSON, the experiment request, the task's
  resolved `TaskEnvironment`, the experiment folder, `Platform`, and a `Promise<TaskOutcome>`.
- **Task to orchestrator, at the end.** The verticle completes the promise with a `TaskOutcome` (evalId, result,
  timing, token usage, harness error if any). The promise completes on the task's thread, so the orchestrator hops back
  to its own context with `runOnContext` before touching experiment state. It then undeploys the verticle.
- **Orchestrator to task, to cancel.** Undeploying the verticle. `stop()` saves what it can, quits the driver and
  closes the listener, as on a timeout.
- **Progress, optional.** If something needs live progress (a dashboard, the timeline app), the task publishes small
  status messages to `task.<evalId>.status`. Nothing in the run depends on them.

### Isolation rule

A task verticle reads shared services and appends through them (SQLite, telemetry), but never changes shared
in-memory state. Token usage, timing, the event log and the agent's state all stay inside the verticle.
`TokenUsageRecord.active` stays only for work outside tasks, such as model construction.

## Task evaluation

Evaluation stays inside the task verticle. Today that is the python script run over the task's event log, and WebArena
tasks have no scoring at all. The plan is to port WebArena's automated evaluators later (out of scope for now). Several
of them check the end state of the browser: the URL, the page content, or values read off the page. So they need the
browser session after the agent finishes and before the driver is quit, which only the task verticle has.

That fixes the order of the end of a task:

1. **The agent finishes**, by completing, failing, hitting the step cap or timing out. The harness stops sending
   instructions but leaves the browser alone. A timeout ends execution, not the task.
2. **Evaluate**, with the browser still open. The evaluators run on the task's worker pool, since Selenium blocks.
3. **Save the artifacts** and the task's `-result.json`.
4. **Report per-task telemetry** through the proxy.
5. **Quit the browser**, then complete the orchestrator's promise with the `TaskOutcome`.

A sketch of the seam, so today's python scoring and the future WebArena evaluators fit the same slot:

```java
interface TaskEvaluator {
    /** Runs on a worker thread. The browser is still open and must not be navigated away by accident. */
    TaskEvaluation evaluate(TaskEndState end);
}

record TaskEndState(JsonObject task, WebDriver driver, Path eventLog, String agentAnswer, TaskTiming timing){}
```

- The evaluators are chosen from the task's definition, as WebArena does with its `eval_types`.
- `TaskTiming.scoringMs` covers the evaluate step, as it does today.
- An evaluator that fails or throws gives an `ERROR` result. It never skips the browser quit.
- `stop()` (undeploy after a crash or cancel) does not evaluate. It only cleans up.
- Some WebArena evaluators query the site's backend rather than the page. Those take the site URLs from the task's
  `TaskEnvironment.sites`, not the browser, and can run the same way.

## Experiment-level telemetry

Experiment telemetry is the orchestrator's job, and it doesn't need anything from inside the task verticles beyond what
they already leave behind.

**How it works today.** `ExplorerVerticle.evaluateHandler` chains the tasks into one future. When it completes:
1. `ExperimentTokenUsage.summarizeAndSave` reads every `<evalId>-tokens.json` in the experiment folder and computes
   min/max/mean/sum for tokens and each timing phase, plus the wall clock from the experiment's start and end.
2. If the request has `evaluationDatasetPath`, the python script is run again over the whole experiment folder to count
   correct and incorrect tasks.
3. Those two are combined into an `ExperimentResults`, reported through `TelemetryVerticle.telemetryService` and
   returned as the HTTP response.

**What carries over unchanged.** The summary is built from files on disk, so it doesn't care which thread or verticle
wrote them. Each task verticle saves its `-tokens.json` before completing its promise, so by the time the orchestrator
summarizes, every file is there.

**What changes:**
- **Waiting for the tasks.** With N at a time, the orchestrator waits on all the task outcomes with `Future.join`, not
  `Future.all`, so one failed task doesn't end the experiment early and still gets counted.
- **Pass/fail from the tasks.** Instead of rerunning the python script over the whole folder, the orchestrator counts
  the results in the `TaskOutcome`s (or the `-result.json` files). This is required once WebArena evaluators exist:
  they need the live browser, so their results can't be recomputed after the fact. It also means an experiment without
  `evaluationDatasetPath` can still report telemetry; today it reports none, which is the WebArena case.
- **Off the event loop.** Summarizing reads files and the telemetry report blocks, so both run in `executeBlocking` on
  the orchestrator's context. Today they run on whichever thread finished the last task.
- **Reading the timing figures under concurrency.** `totalTaskExecution` and `totalInference` are sums over tasks, so
  with tasks in parallel they exceed the wall clock. That is correct, but the experiment needs two new fields to make
  it readable: `maxConcurrentTasks` from the request, and the achieved parallelism, `executionMs.sum / wallClockMs`.
  The per-task means and maxima mean what they mean now, but they include any slowdown from tasks sharing the LLM
  endpoint and the machine. Comparing against sequential runs should keep that in mind.
- **Skipped tasks and the wall clock** stay with the orchestrator, which is the only thing that sees the whole
  experiment.

## Threading inside the verticle

- **The event loop** does socket input and output, the agent's decisions and timers.
- **Blocking work goes to a worker pool:** Selenium, Neo4j queries and path construction, synchronous LLM calls
  (Snippet2XML, the planner), image processing, file and python work.
- **One named pool can serve all task verticles** (e.g. `task-pool`, sized for the concurrency you want). Ordered
  `executeBlocking` is ordered per context, so each task's jobs stay in order without queuing behind other tasks.
- That removes all four blockers from the analysis doc:
  - the sleeps become timers;
  - each task gets its own context;
  - Snippet2XML's and Qwen's blocking jobs are ordered only within their task;
  - the planner's LLM calls are wrapped in `executeBlocking` on the worker pool instead of blocking an event loop.
- **Caveat:** Vert.x has `2 × cores` event loops. With more tasks than that, verticles share loops, so keeping blocking
  work off the loop still matters.

## Lifecycle

1. **Deploy.** The orchestrator deploys the task verticle with the task, its resolved `TaskEnvironment`, the platform
   handles and a completion promise.
2. **`start()`:**
   - build the agent and `LlmCallScope`, then open the listener (getting its port);
   - set up the browser on a worker thread with `guidanceHost = <ip>:<port>`;
   - wait for the Control and Event sockets, with a timeout;
   - start the task.
3. **On completion.** Evaluate with the browser still open, save the artifacts, report telemetry, quit the browser on a
   worker thread, then complete the orchestrator's promise. See [Task evaluation](#task-evaluation) for the order.
4. **Undeploy.** Undeploying closes the verticle's HTTP server. `stop()` must also clean up whatever is left (driver,
   files) after a failure or timeout. Check whether Vert.x 4.5 cancels a context's timers on undeploy; if not, cancel the
   timeout timers explicitly.

## Statics and globals that would go away or move

- `ClientRegistry`, the websocket handler in `GuidanceVerticle` and the `clientMap` concept.
- The `GuidanceVerticle._vertx` uses in `RequestManager`, `GuidanceConnectionManager` and `EventConnectionManager`. Use
  the verticle's own `vertx` and context instead.
- Reads of `LogPreprocessor.*`, `TaskPlannerVerticle.service`, `Snippet2XMLVerticle.snippet2XML`,
  `ExplorerVerticle.sqliteService` and `TelemetryVerticle.telemetryService` on the task path, and the casts to their
  implementations. `Platform` and per-task proxies replace them.
- The thread per task in `ExplorerVerticle` (`new Thread(evaluateTask)`).

## Suggested migration order

Each step should be shippable and smoke-testable on its own.

1. **Small fixes in place.** Replace the two `Thread.sleep` calls with timers of the same length, and pass `Vertx` into
   the managers instead of reading `GuidanceVerticle._vertx`.
2. **Extract a `TaskRuntime` class.** It bundles the client, the harness, the agent, the scope and the artifacts, taken
   out of `EvaluateTask`. It keeps running under today's `GuidanceVerticle`, so behaviour shouldn't change.
3. **Add `TaskVerticle`.** It hosts `TaskRuntime` and its own listener (option A). The orchestrator deploys it per task,
   still one at a time. The browser code moves into `executeBlocking`.
4. **Simplify the LLM services.** Make the scoped planner and Snippet2XML plain in-verticle objects, wrap their
   synchronous LLM calls in `executeBlocking`, and drop the per-task event-bus binding.
5. **Remove the shared websocket path.** Delete `ClientRegistry` and the shared websocket handler.
6. **Turn on concurrency.** Add task-level `environment` fields and `TaskEnvironment.resolve`, which can ship on its
   own before this step, and a `maxConcurrentTasks` setting. Then run two uncharted tasks on one OdoBot, then charted.

## Implementation notes

Steps 1–6 were implemented on 2026-10-12. Step 1 was smoke-tested against the baselines (`baseline-*` vs `step1-*` in
`execution_events`); steps 2–6 share one set of smoke tests at the end.

Where the code is, and where it differs from the design above:

- **No `TaskRuntime` class.** `EvaluateTask` is gone. Its parts went to `task.TaskVerticle` (lifecycle, sockets,
  harness, artifacts), `task.TaskBrowser` (Selenium, blocking) and `task.TaskScorer` (python scoring and telemetry,
  blocking). `task.TaskSettings` holds the request-level helpers (`llmConfig`, `qwenConfig`, `modelDescription`).
- **Sockets.** `guidance.TaskSockets` replaces `ClientRegistry`: one expected `clientId`, its `OdoClient`, and the ready
  promise. `TaskSockets.accept` is the listener's websocket handler. `OdoXServerOptions` holds the TLS and frame-size
  settings shared with `GuidanceVerticle`, which no longer accepts websockets. `strictClientIds` is gone.
- **`Platform` is built per request**, from the statics the service verticles publish (`Platform.current`), not by
  chaining `MainVerticle`'s deployments. The task path no longer reads those statics itself. A charted request that
  arrives before the charted services are up gets a 503 instead of failing mid-task. Chaining the startup is still
  worth doing later.
- **The scoped planner and Snippet2XML** are plain objects in the task, from `withScope(scope)`. The planner's
  synchronous strategy calls run in unordered `executeBlocking` jobs (`TaskPlannerServiceImpl.llm`). `withScope` doesn't
  take a `Context`: `vertx.executeBlocking` already runs on the calling (task's) context.
- **Vector service and telemetry fixes** are in: embeddings run in unordered blocking jobs and the vector query in an
  ordered one (`SqliteVectorService.create(Vertx, JsonObject)`), and Elasticsearch indexing runs in a blocking job.
  Tasks reach telemetry and SQLite through their own proxies.
- **Orchestrator.** `ExplorerVerticle.evaluateHandler` validates every task's environment up front (400 on a bad one),
  then deploys up to `maxConcurrentTasks` (request field, default 1) task verticles at a time on the shared `task-pool`
  (`taskWorkerPoolSize` in explorer.yaml, default 16). Every task reports a `TaskOutcome`, even when it fails to deploy,
  so one failure doesn't end the experiment. The summary and experiment scoring run in a blocking job. The summary has
  `maxConcurrentTasks` and `parallelism`.
- **Environments.** `task.TaskEnvironment.resolve` as designed. The resolved environment is saved in each task's
  tokens file. The charted agent gets the task's `startUrl` as its `userLocation`; a different `userLocation` in the
  task is ignored with a warning.

- **Step limit** (added after the first final smoke run looped for 467 steps): a task's `maxSteps` (default 30) caps
  the agent's instructions the harness executes, in `RequestManager`. Follow-ups don't count. A charted task can set
  `ignoreMaxSteps: true` to turn it off; other agents can't. The tokens file records `steps` and `maxSteps`.

Not done yet, deliberately:

- **The charted agent's Neo4j work** (localizing, `constructV3`) still runs on the task's event loop. It used to block
  the shared guidance loop, so this is already better, but moving it to a worker means changing `ChartedAgent`.
- **Saving the raw event log** stays on the event loop, since OdoX's events keep arriving on it until the browser quits.
- **Experiment pass/fail still comes from rerunning the python script** over the experiment folder, not from the task
  outcomes. That has to change once WebArena evaluators exist.
- **No evaluator seam yet.** `TaskScorer` is today's python scoring; the `TaskEvaluator` interface comes with the
  WebArena evaluators.
- **Tests.** Socket routing is tested with real websockets on per-task ports (`TaskSocketsTest`), and pacing, environment
  resolution and the summary are unit tested. There is no test that runs a whole `TaskVerticle` with a fake OdoX, since
  that needs a stand-in for `TaskBrowser`; the smoke tests cover it.
- `TaskBrowser` retries setup at most 5 times when OdoX's options page isn't ready; `EvaluateTask` retried without limit.

## Smoke test results

Run on 2026-10-12 against `aianta/odobot:migration`, all with `gpt-5.6-luna` (also driving the uncharted Qwen38Agent,
with its Qwen prompts unchanged). Artifacts are under `execution_events/final-*`; baselines under `baseline-*`.

| Test | Result |
|---|---|
| 1. Sequential regression, WebArena uncharted | Same artifacts and ~4.7 s of non-inference time per step as `baseline-webarena-uncharted-luna`; per-task port; new tokens fields; ended at the 30-step limit. The baseline image logged 57 blocked-event-loop warnings, the migration none. |
| 1. Sequential regression, Canvas charted | Both tasks completed and scored correct, same network-request counts as the baseline. Task 3, which timed out in both earlier runs, completed. |
| 2. Five WebArena tasks at once | Each on its own port with its own OdoX client; no task's events contain another's host; parallelism 2.76; no blocked loop; inference per call not slower than sequential. |
| 3. Failure isolation | The task with an unreachable start page failed at setup, wrote its tokens file, and didn't affect the others. |
| 4. Two Canvas charted tasks at once | Both completed and correct, same steps and event counts as sequential, each on its own Canvas; wall clock 236 s → 130 s (parallelism 1.85). |
| 5. No leftovers | Grids have no sessions, no task listeners remain, the task pool's threads are gone. Neo4j driver I/O threads grew from 4 to 20 as a bounded pool filled, not per task. |
| 6. Staggered requests | Request A ran; B and C, submitted 30 s later as separate requests, started immediately and ran alongside it, each with its own experiment folder and summary. |

Bugs the smoke tests found, fixed on the branch:
- **No step limit**: an uncharted task with Luna looped for 467 steps. Added `maxSteps` (default 30) and charted-only
  `ignoreMaxSteps`.
- **Empty event logs for completed tasks**: `TaskVerticle` saved the raw events after `RequestManager.taskComplete()`
  had cleared them. It now saves them in the completion callback, and `taskComplete()` no longer clears them.
- **The request validator required `webAppURL` and the LogUI fields** at the experiment level; they may now come from
  each task's `environment`.

Environment findings, not caused by the migration:
- Luna answers the Qwen prompt with pixel coordinates where it asks for a 1000×1000 grid, so its clicks mostly miss on
  WebArena (the same on the baseline image). WebArena runs with Luna test the harness, not the agent.
- Postmill (reddit) sends `Content-Security-Policy: default-src 'self'`, which blocks OdoX's guidance socket, so its
  instructions never reach the page.
- The `shopping` image's Magento base URL had to be set to its `odobot-net` address.

## Instruction pacing

**Decided 2026-10-12: keep the new behaviour.**

Two mechanisms decide when an instruction reaches OdoX. The pause waits 3 s before each `EXECUTE` (1 s for a follow-up
or `SHOW_NAVIGATION_OPTIONS`). The settle delay restarts a 5 s timer on every DOM effect, network event or location
change, capped at 30 s, and `send()` holds an `EXECUTE` while it runs.

The pause used to be a `Thread.sleep` on the event loop that all OdoX sockets share. OdoX's messages from the previous
action queued up during it, so an instruction was only held if the settle timer was already running when the pause
began. Since step 1 the pause is a timer. Changes arriving during the pause are seen and hold the instruction until the
page has been quiet for 5 s. That is what the settle delay was meant to do, but instructions issued while the page is
still changing now go out later than before, and results may differ from the pre-migration baseline because of it.



Three layers of tests, from cheap to expensive. The first two run on every step; the third runs at checkpoints.

### Layer 1: unit tests (JUnit, no browser, no LLM)

Add `vertx-junit5` so verticles can be deployed in tests (`VertxExtension`, `VertxTestContext`). In these tests, set
`VertxOptions.setBlockedThreadCheckInterval` low and fail the test on any "Thread blocked" warning. That turns "nothing
blocks an event loop" from a review comment into a check.

### Layer 2: verticle tests with a fake OdoX

A test helper that plays OdoX: a websocket client that connects with a `clientId` and `source` in the URL and sends
canned frames. The frames come from a real run's saved raw event log, so they have the real shapes and sizes, including
the 10 MB DOM frames. Paired with a scripted agent (or the Qwen client stubbed with recorded responses, as
`QwenGoldenTrajectoryTest` does), this runs a whole task inside a `TaskVerticle` with no Firefox and no LLM. A mocked
`WebDriver` stands in for the browser.

### Layer 3: smoke runs against real environments

Real docker deployment, pinned Firefox image, `gpt-5.6-luna`. Rebuild the image before each run and check in before
starting it.

**Take a baseline first.** Before step 1, run the single-task WebArena uncharted smoke test and the Canvas charted smoke
test on today's code and keep the artifacts. Each later smoke run is compared against them. The LLM isn't
deterministic, so the comparison is about shape, not content:
- the same artifact files exist, with the same names and the same JSON keys;
- the raw event log scores, and with the same result where the task is stable;
- the tokens file has every timing phase and the same LLM call types; counts and durations are in the same range;
- no new errors or "Thread blocked" warnings in the log.

### Per migration step

| Step | Unit and verticle tests | Smoke |
|---|---|---|
| 1. Timers for sleeps, injected `Vertx` | Extend `GuidanceConnectionManagerSettleDelayTest`: the instruction is sent no earlier than 1 s / 3 s, and the event loop is free in between | Baseline comparison, both tasks |
| 2. Extract `TaskRuntime` | `buildAgent` gives the right agent per mode; the artifact file names for an evalId are unchanged | Baseline comparison. A pure refactor, so any difference is a bug |
| 3. `TaskVerticle` with its own listener | Port `ClientRegistryTest`'s cases to the verticle: ready only once Control and Event are bound; the Guidance socket alone isn't enough; other `clientId`s refused; a reconnect replaces the socket; the setup timeout fails the task. The listener reports a real port, and after undeploy the port refuses connections and the driver was quit. `TaskEnvironment.resolve`: task over experiment over default, task `userLocation` ignored with a warning, a task without a resolvable `startUrl` rejected with a 400 | Baseline comparison. Also confirm OdoX's options page shows `odobot:<port>` and the self-signed cert is accepted on that port |
| 4. LLM services in the verticle | `withScope(scope, context)` runs its blocking jobs on the task's context; two scoped planners used at once count tokens only to their own task. Vector service: N concurrent lookups with a stubbed, delayed embedding take about one delay, not N. Telemetry: `reportTaskResults` doesn't block the caller's loop | Canvas charted baseline comparison: same LLM call types and counts in the tokens file |
| 5. Remove the shared websocket path | Everything compiles without `ClientRegistry`; the old `:7080` socket path is gone | Baseline comparison, both tasks |
| 6. Concurrency | Deploy N task verticles at once, each with its own fake OdoX, with frames interleaved: every task's event log has only its own frames, every task completes, nothing blocks. One task failing doesn't stop the others, and the experiment summary still counts it (`Future.join`). `ExperimentTokenUsageTest` covers the new `maxConcurrentTasks` and parallelism fields | The end-of-migration WebArena smoke tests below |

### End-of-migration WebArena smoke tests

Every task gets its own `environment` block with its own Firefox grid and app instances, as the external environment
manager would provide.

1. **Single task, sequential.** The 1-task uncharted smoke test with `maxConcurrentTasks: 1`, compared against the
   baseline. Checks that nothing regressed when there's no concurrency.
2. **Same site, in parallel.** The 5-task shopping-admin smoke test with `maxConcurrentTasks: 5`, each task in its own
   environment. Checks:
   - every task completes, and its event log has only its own environment's host;
   - each task's OdoX connected to its own port;
   - per-task step counts and inference time per call are close to the sequential run, so tasks aren't slowing each
     other down much;
   - the experiment's wall clock is well below the sum of the tasks' execution times, and the parallelism field shows
     it.
3. **Mixed sites, in parallel.** Four tasks across shopping-admin, shopping, reddit and gitlab, as in
   `resource-use-sampling.json`, run together. Checks that per-task `startUrl` and `targetHosts` reach the right
   browser and OdoX: each event log has only its own site's hosts, and no task records another's traffic.
4. **Sequential vs parallel.** Run the same set as test 2 or 3 at `maxConcurrentTasks: 1` and at the full count.
   Compare pass/answer outcomes, step counts, inference time per call and memory use. This is the run that tells you
   whether concurrency changes results, not just whether it works.
5. **Failure isolation.** Three tasks in parallel: one normal, one whose `startUrl` is unreachable, and one with a very
   short `timeout`. The normal task finishes unaffected. The others end with a recorded harness error or timeout and
   still produce a tokens file. The experiment summary counts all three.
6. **No leftovers.** After tests 2 to 5: the grids report no open sessions, the OdoBot container has no listening
   ports beyond its fixed ones, and the thread count and heap are back near where they started. A second run of
   test 2 in the same OdoBot process behaves like the first.
7. **Old requests still work.** An existing request with only experiment-level `webAppURL`, `firefoxDockerGridURL`
   and tasks with `userLocation` runs as before, with the `userLocation` warning in the log.

At the end, the Canvas charted smoke test should also run concurrently (two tasks, two Canvas environments), since the
charted path uses the shared services (Neo4j, the planner, the vector service) that WebArena uncharted runs don't touch.

## Open questions

- **Porting the WebArena evaluators.** They are written in Python against Playwright. They could be rewritten in Java
  against Selenium, or kept in Python and given the end state, though a Python process can't easily share the task's
  Selenium session. Rewriting the page-reading parts in Java is probably simpler.
