# OdoBot 

# Requirements

In order to reproduce the CASCON 2026 experiment [docker](https://docs.docker.com/engine/install/) must be installed.


# Installation 

1. Clone this git repository. `git clone git@github.com:aianta/odo-bot.git`
2. Set `OPENAI_API_KEY` and `OPENAI_MODEL` environment variables with your api key, and the name of the model you wish agents to use in experiments. 
3. Run `./cascon-environment-setup.sh` on linux/WSL or `cascon-environment-setup.bat` on windows. 

The `cascon-environment-setup` script does the following:
* Downloads the application behavioral model constructed from trajectories on Canvas LMS from Zenodo and unpacks them into the `/db` folder.
* Downloads the `odobot` [docker image](https://hub.docker.com/r/aianta/odobot). Note that pulling this replaces whatever `aianta/odobot:latest` refers to locally, so a local `docker build` of the image will lose the tag.
* Downloads the `canvas-bench` [docker image](https://hub.docker.com/r/aianta/canvas-bench) containing a version of Canvas LMS loaded with the test data required to complete the evaluation tasks used in the CASCON 2026 paper.
* Downloads the [`selenium/standalone-firefox`](https://hub.docker.com/r/selenium/standalone-firefox) docker image, the browser OdoBot drives during experiments. The Dev Edition channel is required because OdoX is installed as an unsigned permanent add-on, and Firefox only honours `xpinstall.signatures.required=false` on Dev Edition, Nightly and ESR builds. The image is pinned by digest (`sha256:a17bbdea…`) rather than to the `:dev` tag, which moves; that digest is Firefox 156.0b3 / geckodriver 0.37.1 / Selenium Grid 4.48.0.
* Copies the example config files inside `/config`, renaming them to drop the `-example` suffix (`taskplanner-example.yaml` -> `taskplanner.yaml`), then sets the OpenAI secret key and model values inside them from `OPENAI_API_KEY` and `OPENAI_MODEL`. The `modelOverride` key in `config/main.yaml` is set to `OPENAI_MODEL` as well, since it takes precedence over the per-service `model` values.


# Running Experiments

The experiment script does the following:
* Starts the OdoBot server container.
* Starts the selenium firefox standalone container.
* Starts the Canvas container.
* Sends the experiment execution request defined in `cascon-experiment.json` to OdoBot for execution. A `-{INSTANCE}` suffix will be appended to the `experimentId` field inside this JSON file by the experiment execution script. 
* Waits for OdoBot to complete execution, then resets the environment and runs the experiment again for the requested number of times (experiment instances).
    

>Note: Experiment results will appear in the `execution_events/{experimentId}-{INSTANCE}` folder, with the evaluation script's output in its `results` subfolder.

>Note: The OdoBot server may complain about being unable to establish a connection to ElasticSearch, this is normal. ElasticSearch is not required to have OdoBot execute tasks. 

>Note: The OdoBot server may complain about various processes taking too long to execute, these errors are also safe to ignore. 

Run `./cascon-experiment.bat <EXPERIMENT DEFINITION FILE> <NUM INSTANCES> [--logs|--no-logs]`.
* `EXPERIMENT DEFINITION FILE`: The file containing the tasks to complete/agent specific execution parameters. To run the full CASCON 2026 experiment use `cascon-experiment.json`, for a simple 3-task smoke test use `cascon-experiment-smoke-test.json`.  
* `NUM INSTANCES`: The number of times the experiment will be repeated. Note: The environment resets only after all tasks are completed.
* `--logs` / `--no-logs`: Whether to capture the OdoBot container's output into `execution_events/<experimentId>-<INSTANCE>/logs/odobot.log`. On by default. OdoBot is verbose (roughly 500KB for a 3-task run), so `--no-logs` skips it; an instance that fails still gets its log written either way. Set the `ODOBOT_CAPTURE_LOGS` environment variable to `1` or `0` to change the default.

While an experiment is running you can watch the browser at `http://localhost:7900` (password: `secret`).




# Running Evaluation

The evaluation script examines experiment artifacts for a given agent and outputs the number of successful/failed tasks.



# Building Docker Image 

```
docker build --progress=plain -t aianta/odobot -f docker-gradle/Dockerfile .    
```

# Running OdoBot via Docker


## Volume Mounts
* `/config`: contains configuration YAML files for OdoBot services
* `/execution_events`: When OdoBot executes tasks on a web application, this folder is populated with logs, telemetry and experiment results.
* `/libs`: External libraries and tools required for OdoBot to function. 
* `/db`: The folder containing SQLite (`odobot.db`) and Embedded Neo4J data. These store the application model OdoBot will use during task execution. 

## Port Bindings
* `8076`: This is the port on which OdoBot exposes its `/api/evaluate` endpoint where tasks can be sent for evaluation.
* `7080`: This is the port on which OdoBot exposes its guidance service

```
docker run -p 8076:8076 -p 7080:7080 -v ./config:/application/config -v ./execution_events:/application/execution_events -v ./libs:/application/libs -v ./db:/application/db  --name odobot aianta/odobot
```

# Running Experiments

`git clone --recurse-submodules git@github.com:aianta/odo-bot.git`



## Environment setup

**Resetting the environment**

`docker rm -f canvas && docker run -d --name canvas -p 8088:80 aianta/canvas-bench:cascon-2026`

**Starting the browser grid**

`docker rm -f selenium-firefox && docker run -d --name selenium-firefox --shm-size=2g -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -p 4444:4444 -p 7900:7900 selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2`

`MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1` is required. Since Firefox 152, WebDriver refuses to navigate to any URL outside the `blob`/`file`/`http`/`https` schemes, and refuses to evaluate scripts against privileged browsing contexts. OdoBot needs both in order to configure OdoX through its `moz-extension://` options and bot control pages; without the variable, setup fails with `Navigation to "moz-extension://[...]/options/options.html" is not allowed in this context`.

The variable cannot be supplied from the OdoBot side — geckodriver rejects both the `--remote-allow-system-access` argument and the environment variable itself when they arrive via capabilities — so it has to be set on the container (or, for a local non-containerised driver, in OdoBot's own environment).

The container's noVNC view is on port 7900 if you want to watch a run.