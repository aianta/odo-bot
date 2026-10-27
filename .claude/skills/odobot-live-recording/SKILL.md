---
name: odobot-live-recording
description: Launch OdoBot with a Selenium Firefox at a target URL with OdoX injected, and record a trajectory by hand through noVNC while the Trajectory Debugger shows the live charted timeline and OdoX's diagnostics. Use when the user wants to open OdoBot's browser at a page (e.g. the WebArena admin dashboard) with OdoX, drive it themselves, watch which events OdoX logs, debug event capture (deaf pages, missing clicks), record human trajectories, or use the Trajectory Debugger (cut, annotate, import, export timelines).
---

# Recording a trajectory by hand

`POST /api/debug/record` on OdoBot's evaluation API (8076) does what a task's set-up does, with no agent: it opens
Firefox on a Selenium grid, installs and configures OdoX, logs in to the sites (WebArena session login), opens the start
page and starts OdoX's transmission. **It answers only once OdoX confirmed the transmission**, with the noVNC link to
drive the browser and the Trajectory Debugger's link (8077). Events go to the debugger's active timeline as they arrive.
One session runs at a time.

Needs an OdoBot image built from code that has the debugger (`debugger/` package, OdoX with GET_DIAGNOSTICS). Reads
outside odo-bot go through the shell. Check in with the user before starting containers (their machine's RAM).

## 1. The app

A WebArena site from the scheduler (its own `wa-*` containers, base URL configured for their IP):

```bash
cd C:/Users/aiant/phdspace/odobleja/WebArenaScheduler
.venv/Scripts/python.exe -m wasched env up shopping_admin --grid     # a stack plus a pinned Firefox grid
.venv/Scripts/python.exe -m wasched env urls shopping_admin          # its base URL, e.g. http://172.23.11.1
.venv/Scripts/python.exe -m wasched env list                         # names, IPs, the grid (wa-grid-<n>)
```

`--grid` publishes the grid's noVNC on `127.0.0.1:7950+n`. Any other app works too, as long as Firefox on `odobot-net`
can reach it. Keep `wa-gateway` running (Docker Desktop reaches container IPs through it).

## 2. Firefox (if not from `--grid`)

Use the pinned image: `:latest` Firefox rejects unsigned OdoX. OdoBot drives OdoX's `moz-extension://` pages, which
needs `MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1`. Publish noVNC (7900) for the person:

```bash
docker run -d --name debug-grid --network odobot-net --ip 172.23.0.20 --shm-size=2g \
  -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -e SE_NODE_SESSION_TIMEOUT=86400 \
  -p 4444:4444 -p 7900:7900 \
  selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2
```

`SE_NODE_SESSION_TIMEOUT` keeps an idle session open (the default ends one after 300 s without a WebDriver command);
OdoBot also touches the session every 60 s while a person records, so a grid without it works too.

## 3. OdoBot

As in the webarena-scheduler skill, with the debugger's port published too:

```bash
docker rm -f odobot
docker run -d --name odobot --network odobot-net --ip 172.23.0.2 -p 7080:7080 -p 8076:8076 -p 8077:8077 \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/config:/application/config" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/execution_events:/application/execution_events" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/libs:/application/libs" \
  -v "C:/Users/aiant/phdspace/odobleja/odo-bot/db:/application/db" aianta/odobot:latest
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8076/api/health      # 200 when up
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8077/                # the debugger
```

`config/main.yaml` must have `TrajectoryDebugger: true`; `config/trajectory-debugger.yaml` sets its port, workspace
(`db/debugger/`) and the default noVNC link.

## 4. Start recording

```bash
curl -s -X POST http://localhost:8076/api/debug/record -H 'content-type: application/json' -d '{
  "startUrl": "http://172.23.11.1/admin/admin/dashboard/",
  "odoSightPath": "./odo-sight.xpi",
  "guidanceServiceHost": "172.23.0.2:7080",
  "firefoxDockerGridURL": "http://172.23.0.20:4444",
  "targetHosts": ["*"],
  "viewerUrl": "http://localhost:7900/?autoconnect=1&resize=scale&password=secret",
  "task": {"environment": {"auth": {"mode": "session", "sites": [{"site": "shopping_admin",
      "baseUrl": "http://172.23.11.1", "loginUrl": "http://172.23.11.1/admin",
      "username": "admin", "password": "admin1234", "loggedInAtStart": true}]}}}
}'
```

- `startUrl` is the page Firefox opens (it wins over a task's). `task` is optional: give one to log in first, in the
  shape of a scheduler request's task (`runs/<run-id>/requests/*.json` `tasks[0]`, wrapped in `odoBotNL` or not); its
  passwords are masked there, so put the real ones back (WebArena's admin: `admin` / `admin1234`).
- `firefoxDockerGridURL` is the grid's address **on odobot-net** (as OdoBot reaches it); `viewerUrl` is noVNC **as the
  person's browser reaches it** (`127.0.0.1:7950+n` for a `--grid` grid). noVNC's password is `secret`.
- The answer (it takes 20-60 s: Firefox, OdoX's options, login) is `{sessionId, viewerUrl, debuggerUrl, startUrl,
  message}`. Give the user both links. 409 means a session already runs (`GET /api/debug/record` shows it); 500 has
  the set-up error (often a wrong grid URL, or a grid not started with `MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1`).

## 5. In the debugger (http://localhost:8077)

- **Timelines tab:** one row per timeline, aligned by position, colour-coded by kind; the active one (red edge)
  receives recorded events, and the first event creates one if none is active. Click a cell for its row, screenshot
  (element outlined), crop, page snapshot and notes. ✂ Cut, then click the entity that ends the first half. Start /
  Stop recording toggles OdoX's transmission (each start sends a fresh START_TRANSMISSION Observation).
- **Import:** OdoBot's dataset (read through a snapshot the trajectory store writes, never the live db) or an
  uploaded `.zip`/`.db`; imported timelines are copies.
- **Download:** a zip laid out like `db/` (`trajectories.db` of schema 3, `screenshots/`, `timelines.json`), readable
  with the load-odobot-results skill.
- **OdoX diagnostics tab:** the pipeline hop by hop (page scripts → content script → background → OdoBot) and the
  first hop that stopped receiving; each injected script (injected / loaded / ran); LogUI (active, elements bound,
  dispatched, init errors); events the background dropped for another LogUI session; page errors; and per page
  whether it is deaf (no page-side event 5 s after loading).

The same over HTTP: `GET /api/session/diagnostics`, `GET /api/workspace`, `POST /api/export {timelineIds}`.

## 6. End and clean up

```bash
curl -s -X DELETE http://localhost:8076/api/debug/record/<sessionId>     # or "End session" in the debugger
docker rm -f debug-grid                                                    # if you started one
cd C:/Users/aiant/phdspace/odobleja/WebArenaScheduler && .venv/Scripts/python.exe -m wasched env down <names...>
```

Ending freezes the last entity, saves the session's raw OdoX events to `db/debugger/sessions/<sessionId>/events.json`
and closes Firefox; the timelines stay in the debugger (saved in `db/debugger/workspace/`).

## Caveats

- One session at a time; a second start gets 409.
- If OdoBot restarts mid-session, the session's last (still live) entity is lost; frozen ones are kept.
- Raw events go to `db/debugger/sessions/<id>/`; DOM effects carry page snapshots, so long sessions on busy pages
  grow large. Delete old session folders when done.
- An OdoX build without GET_DIAGNOSTICS shows "doesn't report diagnostics"; OdoBot's own numbers still show.
