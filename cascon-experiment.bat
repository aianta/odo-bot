@echo off
setlocal EnableExtensions
REM ============================================================================
REM  cascon-experiment.bat <TASK FILE> <NUM INSTANCES> [--agent NAME] [--logs|--no-logs]
REM
REM  Runs the CASCON 2026 experiment with one of three agents - OdoBot, Agent-E
REM  or WebVoyager - against the same containerised Canvas environment.
REM
REM  For each instance it:
REM    1. Resets the environment (recreates the containers the chosen agent
REM       needs, so every instance starts from identical state)
REM    2. Builds a per-instance copy of the task file with the running Canvas
REM       container's address written into it
REM    3. Runs the agent and waits for it to finish
REM    4. Scores the run and writes results\<experimentId>-results.json
REM
REM  OdoBot is driven through its HTTP API and evaluates itself inside its own
REM  container. The two baselines are driven with `docker run` and scored here,
REM  because OdoBot's /api/evaluate endpoint only understands odoBot/odoBotNL
REM  (see EvaluateTask.taskToExecutionRequest) - there is no way to hand it an
REM  Agent-E or WebVoyager run.
REM
REM  All three agents run the same 46 Side-effect task instances and produce the
REM  same results\<experimentId>-results.json schema, so runs are comparable.
REM
REM  The task file on disk is never modified; per-instance copies go to %TEMP%.
REM
REM  Requires: docker, PowerShell 5.1+, and a completed cascon-environment-setup.
REM  The baselines additionally need host python with `regex` and `tzdata`, which
REM  cascon-environment-setup.bat installs.
REM ============================================================================

set "SCRIPT_PATH=%~f0"
pushd "%~dp0"
set "PROJECT_DIR=%CD%"
REM Forward slashes so the -v arguments are unambiguous to docker.
set "PROJECT_DIR_U=%PROJECT_DIR:\=/%"

REM ---------------------------------------------------------------------------
REM  Configuration
REM ---------------------------------------------------------------------------

set "ODOBOT_IMAGE=aianta/odobot:latest"
set "CANVAS_IMAGE=aianta/canvas-bench:cascon-2026"
set "SELENIUM_IMAGE=selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2"
set "AGENTE_IMAGE=aianta/agent-e-cascon:latest"
set "WEBVOYAGER_IMAGE=aianta/webvoyager-cascon:latest"

REM All containers share a user-defined bridge network so they can reach each
REM other directly. Addresses on this network are what get written into the
REM per-instance task file.
set "NETWORK=odobot-net"

set "ODOBOT_NAME=odobot"
set "CANVAS_NAME=canvas"
set "SELENIUM_NAME=selenium-firefox"
REM The baseline agents run in a container of their own, one instance at a time.
set "AGENT_NAME=cascon-agent"

REM The dataset the evaluation script scores against. Relative to the
REM canvas-evaluation-scripts submodule, which is also where the script has to
REM run from - evaluation_script.py does `from core import *`.
set "EVAL_DATASET=sample_generated_data/cascon-2026/tasks.json"
set "EVAL_SCRIPT=evaluation_script.py"

REM Where the generated per-instance task files go, and the file the PowerShell
REM step uses to hand values back to this script.
set "INSTANCE_DIR=%TEMP%\odobot-experiments"
set "ODO_EXP_RESULT=%INSTANCE_DIR%\current-instance.path"
set "ODO_EXP_LOGPID=%INSTANCE_DIR%\current-logtail.pid"

REM Whether to capture the agent container's output into each experiment's
REM logs\ folder. OdoBot is very chatty (~500 KB for a 3-task run), so this can
REM be turned off. Override with the ODOBOT_CAPTURE_LOGS environment variable, or
REM per-run with the --logs / --no-logs argument.
REM When off, a failing instance still gets its log written, so there is always
REM something to diagnose a failure with.
set "CAPTURE_AGENT_LOGS=1"
if defined ODOBOT_CAPTURE_LOGS set "CAPTURE_AGENT_LOGS=%ODOBOT_CAPTURE_LOGS%"

REM Host-published ports. Only used for talking to the containers from here;
REM container-to-container traffic uses the network addresses instead.
set "ODOBOT_API_PORT=8076"
set "ODOBOT_GUIDANCE_PORT=7080"
set "CANVAS_PORT=8088"
set "SELENIUM_PORT=4444"
set "SELENIUM_VNC_PORT=7900"

REM ---------------------------------------------------------------------------
REM  Arguments
REM ---------------------------------------------------------------------------

set "EXPERIMENT_FILE=%~1"
set "NUM_INSTANCES=%~2"
set "EXPERIMENT_FILE_ABS=%~f1"
set "AGENT=odobot"

if "%EXPERIMENT_FILE%"=="" goto :usage
if "%NUM_INSTANCES%"=="" goto :usage

REM Options may appear in any order after the two positional arguments.
shift
shift
:parse_options
if "%~1"=="" goto :parse_done
if /i "%~1"=="--agent" (
    if "%~2"=="" (
        echo [ERROR] --agent needs a value: odobot, agent-e or webvoyager.
        goto :fail
    )
    set "AGENT=%~2"
    shift
    shift
    goto :parse_options
)
if /i "%~1"=="--no-logs" (
    set "CAPTURE_AGENT_LOGS=0"
    shift
    goto :parse_options
)
if /i "%~1"=="--logs" (
    set "CAPTURE_AGENT_LOGS=1"
    shift
    goto :parse_options
)
echo [ERROR] Unknown option: %~1
goto :usage
:parse_done

REM Accept a few spellings so the agent names from the README and the docker
REM image tags both work.
if /i "%AGENT%"=="odoBot" set "AGENT=odobot"
if /i "%AGENT%"=="odoBotNL" set "AGENT=odobot"
if /i "%AGENT%"=="agente" set "AGENT=agent-e"
if /i "%AGENT%"=="agent_e" set "AGENT=agent-e"
if /i "%AGENT%"=="webVoyager" set "AGENT=webvoyager"

set "AGENT_LABEL="
if /i "%AGENT%"=="odobot" (
    set "AGENT=odobot"
    set "AGENT_LABEL=OdoBot"
)
if /i "%AGENT%"=="agent-e" (
    set "AGENT=agent-e"
    set "AGENT_LABEL=Agent-E"
    set "AGENT_IMAGE=%AGENTE_IMAGE%"
    set "AGENT_OUT_LEAF=artifacts"
)
if /i "%AGENT%"=="webvoyager" (
    set "AGENT=webvoyager"
    set "AGENT_LABEL=WebVoyager"
    set "AGENT_IMAGE=%WEBVOYAGER_IMAGE%"
    set "AGENT_OUT_LEAF=webvoyager"
)
if not defined AGENT_LABEL (
    echo [ERROR] Unknown agent: %AGENT%
    echo         Expected one of: odobot, agent-e, webvoyager
    goto :fail
)

if not exist "%EXPERIMENT_FILE_ABS%" (
    echo [ERROR] Task file not found: %EXPERIMENT_FILE%
    goto :fail
)

echo %NUM_INSTANCES%| findstr /r /c:"^[1-9][0-9]*$" >nul
if errorlevel 1 (
    echo [ERROR] NUM INSTANCES must be a positive integer, got: %NUM_INSTANCES%
    goto :fail
)

REM ---------------------------------------------------------------------------
REM  Preflight
REM ---------------------------------------------------------------------------

set "PREFLIGHT_FAILED="

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] docker was not found on PATH.
    set "PREFLIGHT_FAILED=1"
)

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] The docker daemon is not reachable. Is Docker Desktop running?
    set "PREFLIGHT_FAILED=1"
)

REM The evaluation script and the task dataset both live in the submodule.
if not exist "canvas-evaluation-scripts\%EVAL_SCRIPT%" (
    echo [ERROR] canvas-evaluation-scripts is empty - the submodule is not checked out.
    echo         Run cascon-environment-setup.bat, or:
    echo           git submodule update --init --recursive
    set "PREFLIGHT_FAILED=1"
)

if "%AGENT%"=="odobot" call :preflight_odobot
if not "%AGENT%"=="odobot" call :preflight_baseline

if not exist "execution_events" mkdir "execution_events"
if not exist "libs" mkdir "libs"

if defined PREFLIGHT_FAILED goto :fail

echo.
echo ============================================================
echo  Agent                 : %AGENT_LABEL%
echo  Task file             : %EXPERIMENT_FILE%
echo  Instances             : %NUM_INSTANCES%
if not "%AGENT%"=="odobot" echo  Model                 : %OPENAI_MODEL%
echo ============================================================

REM ---------------------------------------------------------------------------
REM  Run each instance
REM ---------------------------------------------------------------------------

for /L %%I in (1,1,%NUM_INSTANCES%) do (
    call :run_instance %%I
    if errorlevel 1 goto :fail
)

echo.
echo All %NUM_INSTANCES% instance^(s^) complete.
if "%NUM_INSTANCES%"=="1" (
    echo Artifacts are under .\execution_events\%BASE_EXPERIMENT_ID%-1\
) else (
    echo Artifacts are under .\execution_events\%BASE_EXPERIMENT_ID%-1\ .. .\execution_events\%BASE_EXPERIMENT_ID%-%NUM_INSTANCES%\
)
echo Each instance's evaluation report is in its results\ subfolder.
if "%CAPTURE_AGENT_LOGS%"=="1" echo Each instance's agent log is in its logs\ subfolder.
echo.
if "%AGENT%"=="odobot" (
    echo The containers are left running so the final state can be inspected
    echo ^(Canvas on http://localhost:%CANVAS_PORT%, browser view on http://localhost:%SELENIUM_VNC_PORT%^).
    echo Stop them with:
    echo   docker rm -f %ODOBOT_NAME% %CANVAS_NAME% %SELENIUM_NAME%
) else (
    echo The Canvas container is left running so the final state can be inspected
    echo ^(http://localhost:%CANVAS_PORT%^). Stop it with:
    echo   docker rm -f %CANVAS_NAME%
)
echo.

popd
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
REM  Preflight, per agent
REM ---------------------------------------------------------------------------

:preflight_odobot
if not exist "config\explorer.yaml" (
    echo [ERROR] config\explorer.yaml is missing. Run cascon-environment-setup.bat first.
    set "PREFLIGHT_FAILED=1"
)
if not exist "db\odobot.db" (
    echo [ERROR] db\odobot.db is missing. Run cascon-environment-setup.bat first.
    set "PREFLIGHT_FAILED=1"
)
exit /b 0

:preflight_baseline
REM The baselines are scored here rather than in-container, so this script needs
REM a working host python. cascon-environment-setup.bat installs the two deps.
call :find_python
if not defined PYTHON_CMD (
    echo [ERROR] No usable python found on PATH ^(tried python3, python, py -3^).
    echo         Python 3.9+ is required to score Agent-E and WebVoyager runs.
    echo         Run cascon-environment-setup.bat, which checks for it.
    set "PREFLIGHT_FAILED=1"
    goto :preflight_baseline_env
)
%PYTHON_CMD% -c "import regex; from zoneinfo import ZoneInfo; ZoneInfo('Canada/Mountain')" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] %PYTHON_CMD% cannot run the evaluation script.
    echo         It needs the 'regex' and 'tzdata' packages. Install them with:
    echo           %PYTHON_CMD% -m pip install --user regex tzdata
    echo         ^(tzdata is required on Windows - there is no system timezone
    echo          database, and loading tasks.json constructs ZoneInfo objects.^)
    set "PREFLIGHT_FAILED=1"
)

:preflight_baseline_env
if not defined OPENAI_API_KEY (
    echo [ERROR] OPENAI_API_KEY is not set - %AGENT_LABEL% cannot run without it.
    set "PREFLIGHT_FAILED=1"
)
if not defined OPENAI_MODEL (
    echo [ERROR] OPENAI_MODEL is not set - %AGENT_LABEL% cannot run without it.
    set "PREFLIGHT_FAILED=1"
)
if "%AGENT%"=="agent-e" call :preflight_agent_e
exit /b 0

:preflight_agent_e
REM Agent-E reads the model name and API key only from this mounted file, never
REM from the environment, and its entrypoint exits 78 without it. Regenerate it
REM from the current environment so Agent-E and WebVoyager always use the same
REM model - otherwise changing OPENAI_MODEL would silently move only WebVoyager.
if not defined OPENAI_API_KEY goto :preflight_agent_e_file
if not defined OPENAI_MODEL goto :preflight_agent_e_file
set "ODO_EXP_STEP=llmconfig"
call :run_powershell
if errorlevel 1 set "PREFLIGHT_FAILED=1"
exit /b 0

:preflight_agent_e_file
if not exist "agents_llm_config.json" (
    echo [ERROR] agents_llm_config.json is missing and cannot be generated
    echo         ^(OPENAI_API_KEY / OPENAI_MODEL are not both set^).
    echo         Run cascon-environment-setup.bat first.
    set "PREFLIGHT_FAILED=1"
)
exit /b 0

REM ---------------------------------------------------------------------------
REM  One experiment instance
REM ---------------------------------------------------------------------------

:run_instance
set "INSTANCE=%~1"

echo.
echo ============================================================
echo  Instance %INSTANCE% of %NUM_INSTANCES%  ^(%AGENT_LABEL%^)
echo ============================================================

if "%AGENT%"=="odobot" goto :run_instance_odobot
goto :run_instance_baseline

REM ---------------------------------------------------------------------------
REM  OdoBot - driven through its HTTP API, evaluates itself in-container
REM ---------------------------------------------------------------------------

:run_instance_odobot

call :reset_environment
if errorlevel 1 exit /b 1

REM Build the per-instance definition. The PowerShell step discovers the
REM container addresses and writes the rewritten copy to %TEMP%, then reports
REM the path back through the ODO_EXP_RESULT file.
echo.
echo [3/4] Building experiment definition for instance %INSTANCE%...
set "ODO_EXP_STEP=config"
call :run_powershell
if errorlevel 1 exit /b 1

call :read_instance_result
if not defined INSTANCE_FILE (
    echo   [ERROR] Failed to determine the generated experiment definition path.
    exit /b 1
)
if not defined FULL_EXPERIMENT_ID (
    echo   [ERROR] Failed to determine the experiment id for this instance.
    exit /b 1
)

call :prepare_dirs

REM Stream the OdoBot container's output into the experiment's own log folder for
REM the duration of the run, unless log capture is turned off.
set "LOG_CONTAINER=%ODOBOT_NAME%"
set "LOG_FILE=%AGENT_LOG%"
if "%CAPTURE_AGENT_LOGS%"=="1" (
    set "ODO_EXP_STEP=logstart"
    call :run_powershell
    if errorlevel 1 exit /b 1
)

echo.
echo [4/4] Submitting experiment to OdoBot and waiting for completion...
echo   POST http://localhost:%ODOBOT_API_PORT%/api/evaluate?agent=odoBotNL
echo   ^(this runs every task in the definition and can take a long time^)
echo.
echo   Watch it live in the browser : http://localhost:%SELENIUM_VNC_PORT%   ^(password: secret^)
if "%CAPTURE_AGENT_LOGS%"=="1" (
    echo   OdoBot log                   : %AGENT_LOG%
) else (
    echo   OdoBot log                   : capture off ^(pass --logs to enable^)
)
echo   Artifacts                    : %ARTIFACT_DIR%\
echo.

REM curl exits 0 on an HTTP error, so the status code is captured separately and
REM checked below - otherwise a failed experiment looks like a successful one.
set "RESP_FILE=%INSTANCE_DIR%\current-response.txt"
set "CODE_FILE=%INSTANCE_DIR%\current-httpcode.txt"

curl.exe -s -S -X POST "http://localhost:%ODOBOT_API_PORT%/api/evaluate?agent=odoBotNL" ^
    -H "Content-Type: application/json" ^
    --data-binary "@%INSTANCE_FILE%" ^
    -o "%RESP_FILE%" ^
    -w "%%{http_code}" > "%CODE_FILE%"
set "CURL_EXIT=%errorlevel%"

set "HTTP_CODE="
if exist "%CODE_FILE%" set /p HTTP_CODE=<"%CODE_FILE%"

REM Stop the tail and replace the file with a complete dump, so the log is whole
REM even if the stream was interrupted.
if "%CAPTURE_AGENT_LOGS%"=="1" (
    set "ODO_EXP_STEP=logstop"
    call :run_powershell
)

if not "%CURL_EXIT%"=="0" (
    echo   [ERROR] Could not reach OdoBot ^(curl exit %CURL_EXIT%^).
    echo   See %AGENT_LOG%
    exit /b 1
)

if not "%HTTP_CODE%"=="200" (
    echo.
    echo   [ERROR] OdoBot returned HTTP %HTTP_CODE% - the experiment did not complete.
    if exist "%RESP_FILE%" type "%RESP_FILE%"
    echo.
    REM Even with capture off, write the log for a failed instance - otherwise there
    REM would be nothing to diagnose the failure with.
    if not "%CAPTURE_AGENT_LOGS%"=="1" (
        set "ODO_EXP_STEP=logstop"
        call :run_powershell
    )
    echo   Note: tasks may have executed before the failure. Check the log for the
    echo   cause, then inspect %ARTIFACT_DIR%\results\ for evaluation output.
    echo   Full log: %AGENT_LOG%
    exit /b 1
)

echo   HTTP %HTTP_CODE%
echo.
echo Instance %INSTANCE% finished.
echo   Artifacts : %ARTIFACT_DIR%\
if "%CAPTURE_AGENT_LOGS%"=="1" echo   OdoBot log: %AGENT_LOG%
exit /b 0

REM ---------------------------------------------------------------------------
REM  Agent-E / WebVoyager - driven with docker run, scored here afterwards
REM ---------------------------------------------------------------------------

:run_instance_baseline

call :reset_environment
if errorlevel 1 exit /b 1

echo.
echo [3/5] Building task file for instance %INSTANCE%...
REM Both baselines hardcode http://localhost:8088 in their task files and have no
REM URL override, so the per-instance copy is rewritten to point at the Canvas
REM container's address on %NETWORK%. This is safe for scoring: core.py keeps only
REM urlparse(url).path, so the host and port never reach the matcher.
set "ODO_EXP_STEP=tasks"
call :run_powershell
if errorlevel 1 exit /b 1

call :read_instance_result
if not defined INSTANCE_FILE (
    echo   [ERROR] Failed to build the per-instance task file.
    exit /b 1
)

call :prepare_dirs
call :mkdir_if_missing "%AGENT_OUT%"

echo.
echo [4/5] Running %AGENT_LABEL% and waiting for completion...
echo   Image     : %AGENT_IMAGE%
echo   Tasks     : %TASK_COUNT%   ^(Canvas at %CANVAS_URL%^)
echo   Model     : %OPENAI_MODEL%
if "%CAPTURE_AGENT_LOGS%"=="1" (
    echo   Agent log : %AGENT_LOG%
) else (
    echo   Agent log : capture off ^(pass --logs to enable^)
)
echo   Artifacts : %AGENT_OUT%\
echo   ^(a full 46-task run takes hours^)
echo.

docker rm -f %AGENT_NAME% >nul 2>&1

if "%AGENT%"=="agent-e" call :start_agent_e
if "%AGENT%"=="webvoyager" call :start_webvoyager
if errorlevel 1 (
    echo   [ERROR] Could not start the %AGENT_LABEL% container.
    exit /b 1
)

REM Stream the container's output while it runs.
set "LOG_CONTAINER=%AGENT_NAME%"
set "LOG_FILE=%AGENT_LOG%"
if "%CAPTURE_AGENT_LOGS%"=="1" (
    set "ODO_EXP_STEP=logstart"
    call :run_powershell
)

REM docker wait blocks until the container exits, then prints its exit code.
set "AGENT_EXIT="
for /f "usebackq tokens=*" %%C in (`docker wait %AGENT_NAME%`) do set "AGENT_EXIT=%%C"

REM Always write the full log for a baseline run: these containers are far less
REM chatty than OdoBot, and without it a failure leaves nothing to diagnose.
set "ODO_EXP_STEP=logstop"
call :run_powershell

docker rm -f %AGENT_NAME% >nul 2>&1

if "%AGENT_EXIT%"=="78" (
    echo   [ERROR] Agent-E exited 78: it could not read /config/agents_llm_config.json.
    echo   See %AGENT_LOG%
    exit /b 1
)
if "%AGENT_EXIT%"=="69" (
    echo   [ERROR] Agent-E exited 69: Canvas was not reachable at %CANVAS_URL%.
    echo   See %AGENT_LOG%
    exit /b 1
)
if not "%AGENT_EXIT%"=="0" (
    echo   [WARN] %AGENT_LABEL% exited %AGENT_EXIT%. Scoring what it produced anyway.
    echo   See %AGENT_LOG%
)

echo.
echo [5/5] Scoring the run...
set "ODO_EXP_STEP=locate"
call :run_powershell
if errorlevel 1 exit /b 1

call :read_instance_result
if not defined EVAL_INPUT (
    echo   [ERROR] Could not locate the %AGENT_LABEL% artifacts to score.
    exit /b 1
)

call :evaluate
if errorlevel 1 exit /b 1

echo.
echo Instance %INSTANCE% finished.
echo   Artifacts : %AGENT_OUT%\
echo   Results   : %RESULTS_FILE%
if "%CAPTURE_AGENT_LOGS%"=="1" echo   Agent log : %AGENT_LOG%
exit /b 0

:start_agent_e
REM CANVAS_URL only drives the entrypoint's reachability probe; pointing it at the
REM rewritten address stops it falling back to its host.docker.internal socat
REM bridge. --shm-size=2g because Chrome's renderer crashes on heavy Canvas pages
REM with Docker's 64 MB default /dev/shm; --init to reap the per-task Chrome
REM processes.
docker run -d --name %AGENT_NAME% --init --shm-size=2g --network %NETWORK% ^
    -e CANVAS_URL=%CANVAS_URL% ^
    -v "%INSTANCE_FILE_U%:/config/tasks.json:ro" ^
    -v "%PROJECT_DIR_U%/agents_llm_config.json:/config/agents_llm_config.json:ro" ^
    -v "%AGENT_OUT_U%:/artifacts" ^
    %AGENTE_IMAGE% ^
    -config /config/tasks.json -id %FULL_EXPERIMENT_ID% >nul
exit /b %errorlevel%

:start_webvoyager
REM The bare `-e VAR` form passes the value in from this environment without ever
REM putting the key on a command line. The task file's directory is mounted rather
REM than the file itself, which is what WebVoyager's own DOCKER.md prescribes.
docker run -d --name %AGENT_NAME% --init --shm-size=2g --network %NETWORK% ^
    -e OPENAI_API_KEY ^
    -e OPENAI_MODEL ^
    -e TEST_FILE=/app/tasks/%INSTANCE_FILE_NAME% ^
    -v "%INSTANCE_DIR_U%:/app/tasks:ro" ^
    -v "%AGENT_OUT_U%:/app/results" ^
    %WEBVOYAGER_IMAGE% >nul
exit /b %errorlevel%

:evaluate
REM evaluation_script.py does `from core import *`, so it has to run with the
REM submodule as the working directory. Input and output paths are absolute.
REM Written with gotos rather than if/else: a caret line-continuation inside a
REM parenthesised block is parsed before the block runs and breaks the command.
pushd "%PROJECT_DIR%\canvas-evaluation-scripts"
if "%AGENT%"=="agent-e" goto :evaluate_agent_e
REM --wv-interact-messages feeds the Information-Seeking path only, which is a
REM stub; it is passed because it is the documented invocation and costs nothing.
%PYTHON_CMD% -X utf8 %EVAL_SCRIPT% -t "%EVAL_DATASET%" -o "%RESULTS_FILE%" --wv-network-logs "%EVAL_INPUT%" --wv-interact-messages "%EVAL_INPUT%"
goto :evaluate_done
:evaluate_agent_e
%PYTHON_CMD% -X utf8 %EVAL_SCRIPT% -t "%EVAL_DATASET%" -o "%RESULTS_FILE%" --agent-e-network-logs "%EVAL_INPUT%"
:evaluate_done
set "EVAL_EXIT=%errorlevel%"
popd
if not "%EVAL_EXIT%"=="0" (
    echo   [ERROR] evaluation_script.py failed ^(exit %EVAL_EXIT%^).
    exit /b 1
)
set "ODO_EXP_STEP=summary"
call :run_powershell
exit /b 0

REM ---------------------------------------------------------------------------
REM  Environment reset - every instance starts from identical container state
REM ---------------------------------------------------------------------------

:reset_environment

if "%AGENT%"=="odobot" (
    echo.
    echo [1/4] Resetting environment...
) else (
    echo.
    echo [1/5] Resetting environment...
)

docker rm -f %ODOBOT_NAME% %CANVAS_NAME% %SELENIUM_NAME% %AGENT_NAME% >nul 2>&1

docker network inspect %NETWORK% >nul 2>&1
if errorlevel 1 (
    echo   creating network %NETWORK%
    docker network create %NETWORK% >nul
    if errorlevel 1 (
        echo   [ERROR] Could not create the %NETWORK% network.
        exit /b 1
    )
)

echo   starting %CANVAS_NAME%
docker run -d --name %CANVAS_NAME% --network %NETWORK% -p %CANVAS_PORT%:80 %CANVAS_IMAGE% >nul
if errorlevel 1 (
    echo   [ERROR] Could not start %CANVAS_NAME%.
    exit /b 1
)

REM The baselines bundle their own browser and never talk to OdoBot, so nothing
REM else needs to be running for them.
if not "%AGENT%"=="odobot" goto :reset_wait

REM MOZ_REMOTE_ALLOW_SYSTEM_ACCESS lets WebDriver drive OdoX's moz-extension://
REM pages. Without it OdoBot fails during setup on Firefox 152+. See README.
echo   starting %SELENIUM_NAME%
docker run -d --name %SELENIUM_NAME% --network %NETWORK% --shm-size=2g ^
    -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 ^
    -p %SELENIUM_PORT%:4444 -p %SELENIUM_VNC_PORT%:7900 %SELENIUM_IMAGE% >nul
if errorlevel 1 (
    echo   [ERROR] Could not start %SELENIUM_NAME%.
    exit /b 1
)

echo   starting %ODOBOT_NAME%
docker run -d --name %ODOBOT_NAME% --network %NETWORK% ^
    -p %ODOBOT_API_PORT%:8076 -p %ODOBOT_GUIDANCE_PORT%:7080 ^
    -v "%PROJECT_DIR_U%/config:/application/config" ^
    -v "%PROJECT_DIR_U%/execution_events:/application/execution_events" ^
    -v "%PROJECT_DIR_U%/libs:/application/libs" ^
    -v "%PROJECT_DIR_U%/db:/application/db" ^
    %ODOBOT_IMAGE% >nul
if errorlevel 1 (
    echo   [ERROR] Could not start %ODOBOT_NAME%.
    exit /b 1
)

:reset_wait
if "%AGENT%"=="odobot" (
    echo.
    echo [2/4] Waiting for services...
    set "WAIT_SCOPE=all"
) else (
    echo.
    echo [2/5] Waiting for Canvas...
    set "WAIT_SCOPE=canvas"
)
set "ODO_EXP_STEP=wait"
call :run_powershell
if errorlevel 1 exit /b 1

exit /b 0

REM ---------------------------------------------------------------------------
REM  Helpers
REM ---------------------------------------------------------------------------

:read_instance_result
REM The PowerShell steps report back as key=value lines. An environment variable
REM would not survive the child process, so they go through a file.
set "INSTANCE_FILE="
set "INSTANCE_FILE_U="
set "INSTANCE_FILE_NAME="
set "INSTANCE_DIR_U="
set "FULL_EXPERIMENT_ID="
set "CANVAS_URL="
set "TASK_COUNT="
set "EVAL_INPUT="
for /f "usebackq tokens=1,* delims==" %%A in ("%ODO_EXP_RESULT%") do (
    if "%%A"=="path"   set "INSTANCE_FILE=%%B"
    if "%%A"=="pathU"  set "INSTANCE_FILE_U=%%B"
    if "%%A"=="name"   set "INSTANCE_FILE_NAME=%%B"
    if "%%A"=="dirU"   set "INSTANCE_DIR_U=%%B"
    if "%%A"=="id"     set "FULL_EXPERIMENT_ID=%%B"
    if "%%A"=="base"   set "BASE_EXPERIMENT_ID=%%B"
    if "%%A"=="canvas" set "CANVAS_URL=%%B"
    if "%%A"=="count"  set "TASK_COUNT=%%B"
    if "%%A"=="eval"   set "EVAL_INPUT=%%B"
)
exit /b 0

:prepare_dirs
set "ARTIFACT_DIR=%PROJECT_DIR%\execution_events\%FULL_EXPERIMENT_ID%"
set "ARTIFACT_DIR_U=%PROJECT_DIR_U%/execution_events/%FULL_EXPERIMENT_ID%"
set "LOG_DIR=%ARTIFACT_DIR%\logs"
set "RESULTS_DIR=%ARTIFACT_DIR%\results"
set "RESULTS_FILE=%RESULTS_DIR%\%FULL_EXPERIMENT_ID%-results.json"
if "%AGENT%"=="odobot" (
    set "AGENT_LOG=%LOG_DIR%\odobot.log"
) else (
    set "AGENT_LOG=%LOG_DIR%\%AGENT%.log"
    set "AGENT_OUT=%ARTIFACT_DIR%\%AGENT_OUT_LEAF%"
    set "AGENT_OUT_U=%ARTIFACT_DIR_U%/%AGENT_OUT_LEAF%"
)
call :mkdir_if_missing "%ARTIFACT_DIR%"
call :mkdir_if_missing "%LOG_DIR%"
call :mkdir_if_missing "%RESULTS_DIR%"
exit /b 0

:mkdir_if_missing
if not exist "%~1" mkdir "%~1"
exit /b 0

:find_python
REM Try python3 first, then python, then the py launcher. Each candidate is
REM validated by actually running it: on Windows both python.exe and python3.exe
REM exist under WindowsApps as Microsoft Store stubs that resolve on PATH but do
REM not run, so `where` is not a reliable test.
if defined PYTHON_CMD exit /b 0
call :try_python python3
if defined PYTHON_CMD exit /b 0
call :try_python python
if defined PYTHON_CMD exit /b 0
call :try_python "py -3"
exit /b 0

:try_python
%~1 -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)" >nul 2>&1
if errorlevel 1 exit /b 0
set "PYTHON_CMD=%~1"
exit /b 0

:run_powershell
REM Runs the PowerShell block at the bottom of this file, selected by ODO_EXP_STEP.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$m = '#PS' + '_TAIL#'; $b = [IO.File]::ReadAllText($env:SCRIPT_PATH); $i = $b.IndexOf($m); if ($i -lt 0) { Write-Host '[ERROR] PowerShell payload not found in script.'; exit 1 }; Invoke-Expression $b.Substring($i + $m.Length)"
exit /b %errorlevel%

:usage
echo.
echo Usage: cascon-experiment.bat ^<TASK FILE^> ^<NUM INSTANCES^> [--agent NAME] [--logs^|--no-logs]
echo.
echo   TASK FILE          The tasks to run. The format depends on --agent:
echo                        odobot      cascon-experiment.json
echo                                    ^(or cascon-experiment-smoke-test.json^)
echo                        agent-e     cascon-experiment-agent-e.json
echo                        webvoyager  cascon-experiment-webvoyager.jsonl
echo   NUM INSTANCES      How many times to repeat the experiment. The
echo                      environment is reset between instances.
echo   --agent NAME       odobot ^(default^), agent-e, or webvoyager.
echo   --logs ^| --no-logs Capture the agent container's output into
echo                      execution_events\^<experimentId^>\logs\. On by default;
echo                      OdoBot is chatty, so --no-logs skips it. A failing
echo                      instance is logged either way. The ODOBOT_CAPTURE_LOGS
echo                      environment variable ^(1 or 0^) sets the default.
echo.
echo Agent-E and WebVoyager need OPENAI_API_KEY and OPENAI_MODEL set, and score
echo their runs with host python. cascon-environment-setup.bat configures both.
echo.
echo Examples:
echo   cascon-experiment.bat cascon-experiment.json 5
echo   cascon-experiment.bat cascon-experiment-agent-e.json 5 --agent agent-e
echo   cascon-experiment.bat cascon-experiment-webvoyager.jsonl 1 --agent webvoyager --no-logs
echo.
popd
endlocal
exit /b 1

:fail
echo.
echo Experiment aborted.
popd
endlocal
exit /b 1

#PS_TAIL#
$ErrorActionPreference = 'Stop'

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Wait-Until($label, $timeoutSeconds, $check) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ok = $false
        try {
            $ok = & $check
        } catch {
            # A check that fails because the thing is not up yet is normal, but a
            # FATAL: one means waiting longer cannot help.
            if ($_.Exception.Message -like 'FATAL:*') { throw }
            $ok = $false
        }
        if ($ok) {
            Write-Host ("  ok   {0}" -f $label)
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Timed out after ${timeoutSeconds}s waiting for $label"
}

# OdoBot logs through slf4j-simple, which writes to stderr. Merging the streams in
# PowerShell would surface them as ErrorRecords and, under $ErrorActionPreference
# 'Stop', throw - so the redirect is done by cmd and PowerShell only sees stdout.
function Get-ContainerLogs($name) {
    return (cmd /c "docker logs $name 2>&1" | Out-String)
}

# The address a sibling container should use to reach $name. Every container is
# attached to exactly one network here, so ranging over the map yields one address.
function Get-ContainerAddress($name) {
    $addr = (docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" $name 2>$null | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($addr)) {
        throw "Could not determine the network address of container '$name'. Is it running?"
    }
    return $addr
}

# Replace the value of a top-level JSON string field, leaving the rest of the
# document byte-for-byte intact. Done textually rather than through
# ConvertFrom-Json/ConvertTo-Json so key order, spacing and the escaping inside
# the task strings all survive untouched.
function Set-JsonStringField($json, $field, $value) {
    $pattern = '("' + [regex]::Escape($field) + '"\s*:\s*")(?:[^"\\]|\\.)*(")'
    if (-not [regex]::IsMatch($json, $pattern)) {
        throw "Field '$field' not found in the experiment definition."
    }
    $escaped = $value.Replace('\', '\\').Replace('"', '\"')
    return [regex]::Replace($json, $pattern, { param($m) $m.Groups[1].Value + $escaped + $m.Groups[2].Value }, 1)
}

# Same, but rewrites every occurrence - the baseline task files repeat the start
# URL once per task. Returns the replacement count through [ref]$count.
function Set-JsonStringFieldAll($json, $field, $value, [ref]$count) {
    $pattern = '("' + [regex]::Escape($field) + '"\s*:\s*")(?:[^"\\]|\\.)*(")'
    $count.Value = ([regex]::Matches($json, $pattern)).Count
    $escaped = $value.Replace('\', '\\').Replace('"', '\"')
    return [regex]::Replace($json, $pattern, { param($m) $m.Groups[1].Value + $escaped + $m.Groups[2].Value })
}

function Get-JsonStringField($json, $field) {
    $pattern = '"' + [regex]::Escape($field) + '"\s*:\s*"((?:[^"\\]|\\.)*)"'
    $m = [regex]::Match($json, $pattern)
    if (-not $m.Success) { throw "Field '$field' not found in the experiment definition." }
    return $m.Groups[1].Value
}

function Write-Result($pairs) {
    [IO.File]::WriteAllLines($env:ODO_EXP_RESULT, [string[]]$pairs, (New-Object System.Text.UTF8Encoding($false)))
}

try {
    switch ($env:ODO_EXP_STEP) {

        'wait' {
            # Canvas publishes a healthcheck; fall back to an HTTP probe if absent.
            Wait-Until "$($env:CANVAS_NAME) healthy" 420 {
                $h = (docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{end}}" $env:CANVAS_NAME 2>$null | Out-String).Trim()
                if ($h) { return $h -eq 'healthy' }
                try { $null = Invoke-WebRequest "http://localhost:$($env:CANVAS_PORT)" -UseBasicParsing -TimeoutSec 5; return $true } catch { return $false }
            }

            # The baselines bring their own browser and never talk to OdoBot, so
            # Canvas is the only service they wait on.
            if ($env:WAIT_SCOPE -ne 'canvas') {

                Wait-Until "$($env:SELENIUM_NAME) grid ready" 240 {
                    $s = Invoke-RestMethod "http://localhost:$($env:SELENIUM_PORT)/status" -TimeoutSec 5
                    return [bool]$s.value.ready
                }

                # The published port answers as soon as the container starts, well
                # before the server binds, so key off the line OdoBot logs once the
                # evaluate endpoint is actually listening.
                Wait-Until "$($env:ODOBOT_NAME) API listening" 900 {
                    $logs = Get-ContainerLogs $env:ODOBOT_NAME
                    if ($logs -match 'Data Generation \(Explorer\) Service service started on port') { return $true }
                    $state = (docker inspect -f "{{.State.Running}}" $env:ODOBOT_NAME | Out-String).Trim()
                    if ($state -ne 'true') { throw "FATAL: the $($env:ODOBOT_NAME) container exited before becoming ready. Check: docker logs $($env:ODOBOT_NAME)" }
                    return $false
                }
            }
        }

        'config' {
            $source = $env:EXPERIMENT_FILE_ABS
            $instance = $env:INSTANCE

            $canvasAddr = Get-ContainerAddress $env:CANVAS_NAME
            $seleniumAddr = Get-ContainerAddress $env:SELENIUM_NAME
            $odobotAddr = Get-ContainerAddress $env:ODOBOT_NAME

            $json = [IO.File]::ReadAllText($source)

            # experimentId gets the instance suffix, which is also the name of the
            # artifact folder OdoBot writes to.
            $baseId = Get-JsonStringField $json 'experimentId'
            $experimentId = "$baseId-$instance"
            $json = Set-JsonStringField $json 'experimentId' $experimentId

            # OdoBot (container) -> Selenium grid.
            $json = Set-JsonStringField $json 'firefoxDockerGridURL' "http://${seleniumAddr}:$($env:SELENIUM_PORT)"

            # Firefox (in the Selenium container) -> OdoBot's guidance service. This
            # value is written into OdoX's options page, so it is resolved by the
            # browser, not by OdoBot.
            $json = Set-JsonStringField $json 'guidanceServiceHost' "${odobotAddr}:$($env:ODOBOT_GUIDANCE_PORT)"

            # Firefox -> Canvas. The path is preserved from the original value, and
            # the default port is left off: OdoX derives its target host from this
            # URL, and an explicit :80 would not match the browser's normalised
            # location once it navigates.
            $oldWebApp = Get-JsonStringField $json 'webAppURL'
            $path = '/'
            try { $path = ([Uri]$oldWebApp).PathAndQuery } catch { $path = '/' }
            $json = Set-JsonStringField $json 'webAppURL' "http://${canvasAddr}${path}"

            $outDir = $env:INSTANCE_DIR
            if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
            $outFile = Join-Path $outDir "$experimentId.json"

            [IO.File]::WriteAllText($outFile, $json, $utf8NoBom)

            Write-Host ("  experimentId         {0}" -f $experimentId)
            Write-Host ("  firefoxDockerGridURL http://{0}:{1}" -f $seleniumAddr, $env:SELENIUM_PORT)
            Write-Host ("  guidanceServiceHost  {0}:{1}" -f $odobotAddr, $env:ODOBOT_GUIDANCE_PORT)
            Write-Host ("  webAppURL            http://{0}{1}" -f $canvasAddr, $path)
            Write-Host ("  definition           {0}" -f $outFile)

            Write-Result @(
                "path=$outFile",
                "id=$experimentId",
                "base=$baseId"
            )
        }

        'tasks' {
            $source = $env:EXPERIMENT_FILE_ABS
            $instance = $env:INSTANCE
            $agent = $env:AGENT

            $canvasAddr = Get-ContainerAddress $env:CANVAS_NAME
            # No explicit port: Canvas listens on 80 inside the network, and an
            # explicit :80 would not match the browser's normalised location once
            # it navigates. Same reasoning as the OdoBot webAppURL rewrite above.
            $canvasUrl = "http://$canvasAddr"

            # Baseline task files carry no experimentId field, so the id is derived
            # from the task file's name.
            $stem = [IO.Path]::GetFileNameWithoutExtension($source)
            if ($stem -like 'cascon-experiment-*') {
                $baseId = 'cascon-' + $stem.Substring('cascon-experiment-'.Length)
            } elseif ($stem -like 'cascon-*') {
                $baseId = $stem
            } else {
                $baseId = "cascon-$stem"
            }
            $experimentId = "$baseId-$instance"

            # Each instance gets its own directory: WebVoyager needs the containing
            # directory mounted, and mounting the shared %TEMP% folder would expose
            # every other run's files to the container.
            $outDir = Join-Path $env:INSTANCE_DIR $experimentId
            if (Test-Path -LiteralPath $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
            New-Item -ItemType Directory -Force -Path $outDir | Out-Null

            $count = 0
            if ($agent -eq 'agent-e') {
                # A JSON array of WebArena-shaped records; the browser goes to start_url.
                $name = 'tasks.json'
                $text = [IO.File]::ReadAllText($source)
                $n = 0
                $text = Set-JsonStringFieldAll $text 'start_url' $canvasUrl ([ref]$n)
                $count = $n
            } else {
                # JSONL, one task per line. run.py navigates to the "web" field and
                # also substitutes it into the model's first message. The pattern
                # requires the quote straight after "web", so "web_name" is untouched.
                $name = 'tasks.jsonl'
                $lines = @(Get-Content -LiteralPath $source | Where-Object { $_.Trim() -ne '' })
                $lines = $lines | ForEach-Object { $n = 0; Set-JsonStringFieldAll $_ 'web' $canvasUrl ([ref]$n) }
                $text = ($lines -join "`n") + "`n"
                $count = $lines.Count
            }

            if ($count -lt 1) { throw "No tasks found in $source" }

            $outFile = Join-Path $outDir $name
            [IO.File]::WriteAllText($outFile, $text, $utf8NoBom)

            Write-Host ("  experimentId  {0}" -f $experimentId)
            Write-Host ("  tasks         {0}" -f $count)
            Write-Host ("  canvas        {0}" -f $canvasUrl)
            Write-Host ("  task file     {0}" -f $outFile)

            Write-Result @(
                "path=$outFile",
                "pathU=$($outFile -replace '\\', '/')",
                "name=$name",
                "dirU=$($outDir -replace '\\', '/')",
                "id=$experimentId",
                "base=$baseId",
                "canvas=$canvasUrl",
                "count=$count"
            )
        }

        'locate' {
            # Find the directory the evaluation script should read, and check that
            # the run actually produced one artifact per task.
            #
            # This check is not cosmetic. evaluation_script.py builds its denominator
            # from the artifacts it finds, not from the task list, so a task that
            # produced no file vanishes from the calculation instead of scoring as a
            # failure - a run that died halfway would report 20/20 rather than 20/46.
            $agent = $env:AGENT
            $expected = [int]$env:TASK_COUNT
            $outRoot = $env:AGENT_OUT

            if ($agent -eq 'agent-e') {
                # Mirrors create_test_results_id() in Agent-E's test/tests_processor.py.
                $evalDir = Join-Path $outRoot "logs\test_results_for_$($env:FULL_EXPERIMENT_ID)\network_logs"
                if (-not (Test-Path -LiteralPath $evalDir)) {
                    throw "Agent-E produced no network_logs directory at $evalDir. Check the agent log."
                }
                $found = @(Get-ChildItem -LiteralPath $evalDir -Filter '*_network_logs.json' -File).Count
            } else {
                # run.py creates one timestamped directory per run.
                $runDirs = @(Get-ChildItem -LiteralPath $outRoot -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime)
                if ($runDirs.Count -eq 0) {
                    throw "WebVoyager produced no run directory under $outRoot. Check the agent log."
                }
                $evalDir = $runDirs[-1].FullName
                # Count the network logs specifically: those are what feed Side-effect
                # scoring. The task<id>\ subdirectories hold interact_messages.json,
                # which only serves the Information-Seeking path.
                $found = @(Get-ChildItem -LiteralPath $evalDir -Filter '*.json' -File |
                           Where-Object { $_.Name -notlike '*token*' }).Count
            }

            Write-Host ("  artifacts     {0}/{1} tasks produced a network log" -f $found, $expected)
            Write-Host ("  scoring       {0}" -f $evalDir)

            if ($found -lt $expected) {
                Write-Host ""
                Write-Host ("  [WARN] {0} task(s) produced no artifact." -f ($expected - $found))
                Write-Host "         The evaluator drops these from its denominator rather than"
                Write-Host "         scoring them as failures, so the %_correct below is computed"
                Write-Host ("         over {0} tasks, not {1}. Treat this as a partial run." -f $found, $expected)
                Write-Host ""
            }

            Write-Result @("eval=$evalDir")
        }

        'summary' {
            $path = $env:RESULTS_FILE
            if (-not (Test-Path -LiteralPath $path)) { throw "No evaluation report at $path" }
            $r = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            Write-Host ""
            Write-Host ("  correct    {0}" -f $r.correct)
            Write-Host ("  incorrect  {0}" -f $r.incorrect)
            Write-Host ("  total      {0}" -f $r.total)
            Write-Host ("  %_correct  {0}" -f $r.'%_correct')
        }

        'llmconfig' {
            # Agent-E takes the model name and API key only from this file. The
            # parameters match the configuration the CASCON results were produced
            # with - note temperature 1 and no top_p, unlike the upstream example,
            # whose temperature 0.0 / top_p 0.001 current reasoning models reject.
            $path = Join-Path $env:PROJECT_DIR 'agents_llm_config.json'
            $agentCfg = [ordered]@{
                model_name        = $env:OPENAI_MODEL
                model_api_key     = $env:OPENAI_API_KEY
                model_base_url    = $null
                llm_config_params = [ordered]@{
                    cache_seed  = $null
                    temperature = 1
                    seed        = 12345
                }
            }
            # The stanza key must match the image's baked AGENTS_LLM_CONFIG_FILE_REF_KEY.
            $cfg = [ordered]@{
                openai_gpt = [ordered]@{
                    planner_agent     = $agentCfg
                    browser_nav_agent = $agentCfg
                }
            }
            [IO.File]::WriteAllText($path, ($cfg | ConvertTo-Json -Depth 6), $utf8NoBom)
            Write-Host ("  agents_llm_config.json -> model {0}" -f $env:OPENAI_MODEL)
        }

        'logstart' {
            # Tail the container into the experiment's log folder. cmd does the
            # stream merge so stdout and stderr land in one file in order.
            $logFile = $env:LOG_FILE
            $cmdLine = 'docker logs -f {0} > "{1}" 2>&1' -f $env:LOG_CONTAINER, $logFile
            $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmdLine -PassThru -NoNewWindow
            [IO.File]::WriteAllText($env:ODO_EXP_LOGPID, $proc.Id.ToString(), $utf8NoBom)
            Write-Host ("  streaming {0} logs -> {1}" -f $env:LOG_CONTAINER, $logFile)
        }

        'logstop' {
            # Kill the tail, then overwrite with a full dump so the file is complete
            # regardless of how the stream ended.
            if (Test-Path -LiteralPath $env:ODO_EXP_LOGPID) {
                $tailPid = (Get-Content -LiteralPath $env:ODO_EXP_LOGPID -Raw).Trim()
                if ($tailPid) {
                    # /T because cmd.exe spawned the docker child.
                    cmd /c "taskkill /T /F /PID $tailPid >nul 2>&1"
                }
                Remove-Item -LiteralPath $env:ODO_EXP_LOGPID -Force -ErrorAction SilentlyContinue
            }
            Start-Sleep -Milliseconds 500
            $logFile = $env:LOG_FILE
            cmd /c "docker logs $($env:LOG_CONTAINER) > `"$logFile`" 2>&1"
            $size = 0
            if (Test-Path -LiteralPath $logFile) { $size = (Get-Item -LiteralPath $logFile).Length }
            Write-Host ("  agent log written ({0:N0} bytes)" -f $size)
        }

        default {
            throw "Unknown experiment step: '$($env:ODO_EXP_STEP)'"
        }
    }
} catch {
    Write-Host ('  [ERROR] ' + $_.Exception.Message)
    exit 1
}

exit 0
