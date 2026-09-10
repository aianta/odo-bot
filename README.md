# OdoBot 
Odobot is a web task execution system that reduces token use by AI agents for known tasks.

The experiment described in the 'Token Efficient Task Execution via Application Behavior Modeling for Web Agents' paper submitted to [CASCON 2026](https://conf.researchr.org/home/cascon-2026), is fully reproducible using this repository. 

The paper's raw results are [available on Zenodo](https://zenodo.org/records/21344539). 

## Reproducible Functionalities
- [x] Host a Canvas LMS environment seeded with data to support the execution of 45 agent evaluation tasks.
  
  Details on the evaluation tasks can be found [here](https://github.com/aianta/canvas-evaluation-scripts/blob/main/sample_generated_data/cascon-2026/README.md).

  To run the environment standalone at port 8088 for the evaluation of your own agent use the command: 

  `docker run -d --name canvas-bench -p 8088:80 aianta/canvas-bench:cascon-2026` 

- [x] Recreate the CASCON 2026 experimental results.
  - [x] Execute OdoBot with the [application behavioral model](https://zenodo.org/records/22666468) used to produce the CASCON 2026 paper results on the Canvas LMS environment.
  - [x] Execute Agent-E on the Canvas environment using the 45 tasks used to produce the results in the CASCON 2026 paper.
  - [x] Execute WebVoyager on the Canvas environment using the 45 tasks used to produce the results in the CASCON 2026 paper.


## Work in progress 
The functionality described below is implemented, but not yet matured into documented reusable features, as such they require manual invocation of various sub-components of OdoBot and other auxiliary software artifacts.

A high-level overview of OdoBot's (messy, evolving) internals can be found [here](./docs/odobot-architecture-manifest.md). 

- [ ] Collecting your own trajectories using the OdoX browser extension. 
- [ ] Constructing custom application behavioral models using your own trajectories.
- [ ] Generating new simulated course/assignment/page/quiz/instructor/student/discussion data to create variations of the Canvas evaluation environment.

> [!NOTE]
> Environment setup and experiment runner scripts were created with the help of Claude Code, and human validated. 
> 
>For brevity and clarity, instructions below are human curated, with optional AI generated details hidden in collapsable sections.


# Requirements

To reproduce the CASCON 2026 experiment you need:

* [docker](https://docs.docker.com/engine/install/)
* GIT
* Python 3.9 or newer on `PATH`.
* OpenAI API Key and credits.
* Windows 11  

# Installation (Windows)


1. Clone this git repository.
2. Open a terminal and set `OPENAI_API_KEY` and `OPENAI_MODEL` environment variables with the desired values.

```
setx OPENAI_API_KEY "sk-***"
setx OPENAI_MODEL "gpt-5.4-mini"
```
3. Run `.\cascon-environment-setup.bat` to download:
    * The [application behavioral model](https://zenodo.org/records/22666468) from Zenodo
    * The docker image for the canvas environment.
    * The docker image for selenium/firefox-standalone (used by OdoBot to interact with Canvas)
    * The docker image for OdoBot itself.
    * The docker image for Agent-E, modified to capture network activity for evaluation.
    * The docker image for WebVoyager, modified to capture network activity for evaluation. 

Additionally, the script also propagates the `OPENAI_API_KEY` and `OPENAI_MODEL` into the correct configuration files/container environment variables for OdoBot, Agent-E, and WebVoyager.   

>[!NOTE]
> If your API key changes, or you want to execute the experiment with a different model, change the environment variables and run `.\cascon-environment-setup.bat` again.

<details>
<summary>AI Generated Details</summary>

The `cascon-environment-setup` script does the following:
* Checks out the [`canvas-evaluation-scripts`](https://github.com/aianta/canvas-evaluation-scripts) submodule, which holds `evaluation_script.py` and the `sample_generated_data/cascon-2026` task dataset that all three agents are scored against. `.gitmodules` records an SSH URL, so if the fetch fails the script retries over HTTPS — that only changes your local clone's config, not the committed `.gitmodules`.
* Installs the two Python packages the evaluation script needs: `regex`, which `core.py` imports, and `tzdata`. `tzdata` is easy to overlook — Windows ships no system timezone database, and loading `tasks.json` constructs `ZoneInfo` objects for the Date-Time reference answers, so without it the evaluator raises `ZoneInfoNotFoundError` before scoring anything. It is not listed in the submodule's `requirements.txt` because evaluation had only ever been run on Linux.
* Downloads the application behavioral model constructed from trajectories on Canvas LMS and unpacks it into the `/db` folder. It is archived on Zenodo as [OdoBot CASCON 2026 Application Behavioral Model](https://doi.org/10.5281/zenodo.22666468) (CC-BY-4.0, ~290MB). The download is checked against a known size and MD5 before extraction, since a truncated transfer otherwise surfaces as a confusing zip error. Only OdoBot uses this model — Agent-E and WebVoyager drive Canvas through the browser, so they run without it.
* Downloads the `odobot` [docker image](https://hub.docker.com/r/aianta/odobot). Note that pulling this replaces whatever `aianta/odobot:latest` refers to locally, so a local `docker build` of the image will lose the tag.
* Downloads the `canvas-bench` [docker image](https://hub.docker.com/r/aianta/canvas-bench) containing a version of Canvas LMS loaded with the test data required to complete the evaluation tasks used in the CASCON 2026 paper.
* Downloads the [`selenium/standalone-firefox`](https://hub.docker.com/r/selenium/standalone-firefox) docker image, the browser OdoBot drives during experiments. The Dev Edition channel is required because OdoX is installed as an unsigned permanent add-on, and Firefox only honours `xpinstall.signatures.required=false` on Dev Edition, Nightly and ESR builds. The image is pinned by digest (`sha256:a17bbdea…`) rather than to the `:dev` tag, which moves; that digest is Firefox 156.0b3 / geckodriver 0.37.1 / Selenium Grid 4.48.0.
* Copies the example config files inside `/config`, renaming them to drop the `-example` suffix (`taskplanner-example.yaml` -> `taskplanner.yaml`), then sets the OpenAI secret key and model values inside them from `OPENAI_API_KEY` and `OPENAI_MODEL`. The `modelOverride` key in `config/main.yaml` is set to `OPENAI_MODEL` as well, since it takes precedence over the per-service `model` values.
* Writes `agents_llm_config.json` for Agent-E, which reads its model name and API key *only* from that file — never from the environment — and whose entrypoint exits `78` if it is missing. The generated parameters (`temperature: 1`, `seed: 12345`, no `top_p`) match the configuration the CASCON 2026 Agent-E results were produced with, rather than the upstream `agents_llm_config-example.json`, whose `temperature: 0.0` / `top_p: 0.001` current reasoning models reject. The file holds your API key in plaintext and is gitignored.

</details>

# Running Experiments (Windows)
An experiment is the exection of all tasks specified in a task file by an agent. 
```
.\cascon-experiment.bat <TASK FILE> <NUM INSTANCES> [--agent NAME] [--logs|--no-logs]
```
* `TASK FILE`: The tasks to run. The format depends on the agent:

  | `--agent` | Full run | Smoke test |
    |---|---|---|
  | `odobot` (default) | `cascon-experiment.json` | `cascon-experiment-smoke-test.json` |
  | `agent-e` | `cascon-experiment-agent-e.json` | `cascon-experiment-agent-e-smoke-test.json` |
  | `webvoyager` | `cascon-experiment-webvoyager.jsonl` | `cascon-experiment-webvoyager-smoke-test.jsonl` |

* `NUM INSTANCES`: The number of times the experiment will be repeated. The environment is reset between instances.
* `--agent NAME`: `odobot` (the default), `agent-e`, or `webvoyager`.
* `--logs` / `--no-logs`: For OdoBot only, captures container logs to a file.

<details>
<summary>AI Generated Details</summary>

* `--logs` / `--no-logs`: Whether to capture the agent container's output into `execution_events/<experimentId>-<INSTANCE>/logs/`. On by default. OdoBot is verbose (roughly 500KB for a 3-task run), so `--no-logs` skips it; an instance that fails still gets its log written either way. Set the `ODOBOT_CAPTURE_LOGS` environment variable to `1` or `0` to change the default.

For each instance the script resets the environment, builds a per-instance copy of the task file, runs the agent, and writes an evaluation report. What it starts depends on the agent:

* **OdoBot** needs the Canvas, Selenium Firefox and OdoBot containers. The experiment definition is POSTed to OdoBot's `/api/evaluate` endpoint, and OdoBot evaluates itself using the copy of the evaluation scripts baked into its own image. A `-{INSTANCE}` suffix is appended to the `experimentId` field from the JSON file.
* **Agent-E and WebVoyager** need only the Canvas container — both bundle their own browser — and are launched with `docker run`. Their experiment id is derived from the task file's name, so `cascon-experiment-agent-e.json` produces `cascon-agent-e-1`, `cascon-agent-e-2`, and so on. They are scored after the run by this script, because OdoBot's `/api/evaluate` endpoint only understands `odoBot` and `odoBotNL` and cannot be handed a baseline run.

>Note: Experiment results appear in `execution_events/{experimentId}-{INSTANCE}`, with the evaluation script's output in its `results` subfolder, for every agent.

>Note: Both baseline task files hardcode `http://localhost:8088` as their start URL and neither agent has a URL override, so the per-instance copy is rewritten to the Canvas container's address on the `odobot-net` bridge. This does not affect scoring — `core.py` keeps only `urlparse(url).path`, so the host and port never reach the matcher.

>Note: A full 45-task baseline run takes hours, and WebVoyager's network dumps can reach ~19MB per task (roughly 1GB per run).


While an OdoBot experiment is running you can watch the browser at `http://localhost:7900` (password: `secret`). The baseline agents run their browsers headless, so there is nothing to watch — follow their log instead.


</details>

## Smoke Test Commands

(2 task instances)

```
 .\cascon-experiment.bat cascon-experiment-smoke-test.json 1
 .\cascon-experiment.bat cascon-experiment-agent-e-smoke-test.json 1 --agent agent-e
 .\cascon-experiment.bat cascon-experiment-webvoyager-smoke-test.jsonl 1 --agent webvoyager
```

## Full Experiment Commands

(45 task instances * 5 instances)

```
 .\cascon-experiment.bat cascon-experiment.json 5
 .\cascon-experiment.bat cascon-experiment-agent-e.json 5 --agent agent-e
 .\cascon-experiment.bat cascon-experiment-webvoyager.jsonl 5 --agent webvoyager
```





# Running Evaluation

Every run is scored by `evaluation_script.py` from the `canvas-evaluation-scripts` submodule, which matches the HTTP requests an agent made against the `answer_key` patterns in `sample_generated_data/cascon-2026/tasks.json`. 

`cascon-experiment.bat` runs it for you and writes the report to `execution_events/<experimentId>/results/<experimentId>-results.json`, then prints the headline numbers:

```
  correct    31
  incorrect  15
  total      45
  %_correct  68.89
```

<details>
<summary>AI Generated Details</summary>

Evaluation is entirely offline: it reads artifacts from disk and needs no access to Canvas.

The three agents produce different artifacts, so the script passes a different flag for each:

| Agent | Artifacts | Evaluation flag |
|---|---|---|
| OdoBot | `execution_events/<experimentId>/*.json` | `--odobot-execution-events` (run inside the OdoBot container) |
| Agent-E | `<experimentId>/artifacts/logs/test_results_for_<experimentId>/network_logs/` | `--agent-e-network-logs` |
| WebVoyager | `<experimentId>/webvoyager/<timestamp>/` | `--wv-network-logs` |

>**Important:** the evaluator builds its denominator from the artifacts it *finds*, not from the task list. A task that produced no artifact disappears from the calculation rather than scoring as a failure, so a run that died halfway through would report `20/20` instead of `20/45`. `cascon-experiment.bat` counts the artifacts against the task count and warns when they do not match. This matters most for WebVoyager, which catches every exception and exits `0` even when the batch aborted early — its exit code is not a reliable signal.

>Note: Information-Seeking tasks are out of scope; that path is a stub in `evaluation_script.py`. The 45 instances all three agents run are the Side-effect tasks, which are scored from the captured network requests.

To score an existing set of artifacts by hand, run the script from the submodule directory (it does `from core import *`, so the working directory matters):

```
cd canvas-evaluation-scripts
python -X utf8 evaluation_script.py ^
  -t sample_generated_data/cascon-2026/tasks.json ^
  -o results.json ^
  --agent-e-network-logs <path to network_logs>
```

</details>

>[!WARNING]
> End of CASCON 2026 experiment reproduction instructions.


# Development Documentation

Documentation below is for development work on OdoBot.


## Building Docker Image 

```
docker build --progress=plain -t aianta/odobot -f docker-gradle/Dockerfile .    
```

### Running OdoBot via Docker


### Volume Mounts
* `/config`: contains configuration YAML files for OdoBot services
* `/execution_events`: When OdoBot executes tasks on a web application, this folder is populated with logs, telemetry and experiment results.
* `/libs`: External libraries and tools required for OdoBot to function. 
* `/db`: The folder containing SQLite (`odobot.db`) and Embedded Neo4J data. These store the application model OdoBot will use during task execution. 

### Port Bindings
* `8076`: This is the port on which OdoBot exposes its `/api/evaluate` endpoint where tasks can be sent for evaluation.
* `7080`: This is the port on which OdoBot exposes its guidance service

```
docker run -p 8076:8076 -p 7080:7080 -v ./config:/application/config -v ./execution_events:/application/execution_events -v ./libs:/application/libs -v ./db:/application/db  --name odobot aianta/odobot
```

## Manual container setup

`cascon-experiment.bat` starts and resets all of these containers for you. The commands below are here for debugging a run, or for inspecting the environment on its own.

### Environment setup

**Resetting the environment**

`docker rm -f canvas && docker run -d --name canvas -p 8088:80 aianta/canvas-bench:cascon-2026`

**Starting the browser grid**

`docker rm -f selenium-firefox && docker run -d --name selenium-firefox --shm-size=2g -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -p 4444:4444 -p 7900:7900 selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2`

`MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1` is required. Since Firefox 152, WebDriver refuses to navigate to any URL outside the `blob`/`file`/`http`/`https` schemes, and refuses to evaluate scripts against privileged browsing contexts. OdoBot needs both in order to configure OdoX through its `moz-extension://` options and bot control pages; without the variable, setup fails with `Navigation to "moz-extension://[...]/options/options.html" is not allowed in this context`.

The variable cannot be supplied from the OdoBot side — geckodriver rejects both the `--remote-allow-system-access` argument and the environment variable itself when they arrive via capabilities — so it has to be set on the container (or, for a local non-containerised driver, in OdoBot's own environment).

The container's noVNC view is on port 7900 if you want to watch a run.