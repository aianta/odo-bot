@echo off
setlocal EnableExtensions
REM ============================================================================
REM  cascon-environment-setup.bat
REM
REM  Prepares a Windows machine to reproduce the CASCON 2026 experiment:
REM    1. Checks out the canvas-evaluation-scripts submodule, which holds the
REM       evaluation script and the task dataset all three agents are scored on
REM    2. Installs the python packages the evaluation script needs
REM    3. Downloads the Canvas LMS application behavioural model from Zenodo
REM       and unpacks it into .\db
REM    4. Pulls the odobot, canvas-bench, agent-e-cascon, webvoyager-cascon
REM       and selenium/standalone-firefox docker images
REM    5. Generates config\*.yaml from config\*-example.yaml and writes
REM       agents_llm_config.json, injecting %OPENAI_API_KEY% and %OPENAI_MODEL%
REM
REM  Requires: docker, PowerShell 5.1+ (ships with Windows 10/11), git, and
REM  python 3.9+ on PATH.
REM ============================================================================

REM %0 refers to the label inside a `call :label`, so capture the path up front.
set "SCRIPT_PATH=%~f0"
pushd "%~dp0"
set "ODO_SETUP_ROOT=%CD%"

REM ---------------------------------------------------------------------------
REM  Configuration
REM ---------------------------------------------------------------------------

REM The published application behavioural model:
REM   "OdoBot CASCON 2026 Application Behavioral Model"
REM   https://doi.org/10.5281/zenodo.22666468   (CC-BY-4.0)
REM
REM 22666468 is the version-specific record. Use it rather than the concept DOI
REM (22666467), which always resolves to the newest version and would silently
REM change which model the experiment runs against.
set "ZENODO_RECORD_ID=22666468"
set "MODEL_ARCHIVE=odobot-model.zip"
set "ZENODO_URL=https://zenodo.org/records/%ZENODO_RECORD_ID%/files/%MODEL_ARCHIVE%?download=1"

REM Checked after the download. The archive is ~290 MB, so a truncated or
REM interrupted transfer is a real possibility, and a partial zip fails during
REM extraction with a much less obvious error than a checksum mismatch.
set "MODEL_ARCHIVE_MD5=aa09e959c72807f30885e8f5314c8967"
set "MODEL_ARCHIVE_SIZE=303333559"

REM The submodule holding evaluation_script.py, core.py and the cascon-2026
REM dataset. .gitmodules records the SSH URL; a reviewer cloning without a
REM GitHub SSH key cannot use it, so the fetch falls back to HTTPS below.
set "EVAL_SUBMODULE=canvas-evaluation-scripts"
set "EVAL_HTTPS_URL=https://github.com/aianta/canvas-evaluation-scripts.git"

REM OdoBot itself. ":latest" is a moving tag - consider pushing and pinning a dated or
REM versioned tag (as canvas-bench does with :cascon-2026) for the archival artifact.
REM
REM NOTE: pulling this replaces whatever "aianta/odobot:latest" refers to locally. If you
REM have built the image yourself (docker build -t aianta/odobot -f docker-gradle/Dockerfile .)
REM your local build loses the tag and is left dangling.
set "ODOBOT_IMAGE=aianta/odobot:latest"

set "CANVAS_IMAGE=aianta/canvas-bench:cascon-2026"

REM Agent-E, one of the two baseline web agents evaluated on the same Canvas tasks as
REM OdoBot. Run it with:
REM   cascon-experiment.bat cascon-experiment-agent-e.json <N> --agent agent-e
REM
REM ":latest" is a moving tag. As of 2026-09-10 it resolves to
REM   sha256:8579c7e5ee815c0231d1c1d1129fbf07244bbb669fd2f251cb3e44410ba09af0
REM which is the image the CASCON 2026 Agent-E results were produced with. Pin the digest
REM here instead if the tag moves.
set "AGENTE_IMAGE=aianta/agent-e-cascon:latest"

REM WebVoyager, the other baseline. Run it with:
REM   cascon-experiment.bat cascon-experiment-webvoyager.jsonl <N> --agent webvoyager
REM
REM ":latest" is a moving tag. Pin it by digest here if it moves away from the image the
REM CASCON 2026 WebVoyager results were produced with:
REM   docker image inspect aianta/webvoyager-cascon:latest --format "{{index .RepoDigests 0}}"
set "WEBVOYAGER_IMAGE=aianta/webvoyager-cascon:latest"

REM The browser OdoBot drives during experiments. The Dev Edition channel is required:
REM OdoX is installed unsigned as a permanent add-on, and Firefox only honours
REM xpinstall.signatures.required=false on Dev Edition, Nightly and ESR builds.
REM
REM Pinned by digest, because ":dev" is a moving tag and no dated tag aliases it. This
REM digest is what ":dev" pointed to on 2026-09-08: Firefox 156.0b3 / geckodriver 0.37.1
REM / Selenium Grid 4.48.0, which is the browser the CASCON 2026 results were produced on.
REM To re-pin to a newer build:
REM   docker pull selenium/standalone-firefox:dev
REM   docker image inspect selenium/standalone-firefox:dev --format "{{index .RepoDigests 0}}"
REM
REM This only pulls the image. The container itself is created by the experiment run
REM script, which must pass MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 to it:
REM
REM   docker run -d --name selenium-firefox --shm-size=2g ^
REM     -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -p 4444:4444 -p 7900:7900 %SELENIUM_IMAGE%
REM
REM Without that variable, Firefox 152+ refuses to let WebDriver navigate to OdoX's
REM moz-extension:// pages and OdoBot fails during setup with
REM "Navigation to moz-extension://[...] is not allowed in this context".
set "SELENIUM_IMAGE=selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2"

REM ---------------------------------------------------------------------------
REM  Step 0 - Preflight checks
REM ---------------------------------------------------------------------------

echo.
echo [0/5] Checking environment...

set "PREFLIGHT_FAILED="

if not defined OPENAI_API_KEY (
    echo   [ERROR] OPENAI_API_KEY is not set.
    echo           Set it with:  setx OPENAI_API_KEY "sk-..."
    echo           then open a new terminal and re-run this script.
    set "PREFLIGHT_FAILED=1"
)

if not defined OPENAI_MODEL (
    echo   [ERROR] OPENAI_MODEL is not set.
    echo           Set it with:  setx OPENAI_MODEL "gpt-5.5-2026-04-23"
    echo           then open a new terminal and re-run this script.
    set "PREFLIGHT_FAILED=1"
)

where docker >nul 2>&1
if errorlevel 1 (
    echo   [ERROR] docker was not found on PATH.
    echo           Install Docker Desktop: https://docs.docker.com/engine/install/
    set "PREFLIGHT_FAILED=1"
)

where powershell >nul 2>&1
if errorlevel 1 (
    echo   [ERROR] powershell was not found on PATH.
    set "PREFLIGHT_FAILED=1"
)

REM The Agent-E and WebVoyager runs are scored on the host rather than inside a
REM container, so a working python is a hard requirement for them.
call :find_python
if not defined PYTHON_CMD (
    echo   [ERROR] No usable python 3.9+ found on PATH ^(tried python3, python, py -3^).
    echo           Install it from https://www.python.org/downloads/ and make sure
    echo           "Add python.exe to PATH" is ticked, then re-run this script.
    echo           Python is needed to score Agent-E and WebVoyager runs.
    set "PREFLIGHT_FAILED=1"
)

if defined PREFLIGHT_FAILED goto :fail

echo   OPENAI_MODEL   = %OPENAI_MODEL%
echo   OPENAI_API_KEY = ^(set^)
echo   docker         = ok
call :report_python

REM ---------------------------------------------------------------------------
REM  Step 1 - Evaluation scripts submodule
REM
REM  canvas-evaluation-scripts holds evaluation_script.py, core.py and the
REM  sample_generated_data/cascon-2026 dataset every agent is scored against.
REM  Without it nothing can be evaluated.
REM ---------------------------------------------------------------------------

echo.
echo [1/5] Evaluation scripts submodule...

if exist "%EVAL_SUBMODULE%\evaluation_script.py" (
    echo   %EVAL_SUBMODULE% is already checked out.
    goto :python_deps
)

where git >nul 2>&1
if errorlevel 1 (
    echo   [ERROR] git was not found on PATH, so the %EVAL_SUBMODULE% submodule
    echo           cannot be fetched. Install git, or download the repository with
    echo           its submodules and re-run this script.
    goto :fail
)

echo   fetching %EVAL_SUBMODULE%...
git submodule update --init --recursive
if not errorlevel 1 goto :submodule_done

REM .gitmodules records an SSH URL. Retry over HTTPS so a reviewer without a
REM GitHub SSH key can still fetch it. This only changes the local clone's
REM config, not the committed .gitmodules.
echo.
echo   [WARN] The submodule fetch failed. Retrying over HTTPS...
git config submodule.%EVAL_SUBMODULE%.url %EVAL_HTTPS_URL%
git submodule sync --recursive
git submodule update --init --recursive
if errorlevel 1 (
    echo   [ERROR] Could not fetch the %EVAL_SUBMODULE% submodule over SSH or HTTPS.
    echo           Check your network connection, then try manually:
    echo             git submodule update --init --recursive
    goto :fail
)

:submodule_done
if not exist "%EVAL_SUBMODULE%\evaluation_script.py" (
    echo   [ERROR] %EVAL_SUBMODULE% is still empty after the fetch.
    goto :fail
)
echo   ok   %EVAL_SUBMODULE%

REM ---------------------------------------------------------------------------
REM  Step 2 - Python packages for the evaluation script
REM
REM  core.py imports `regex`. It also builds ZoneInfo objects while loading
REM  tasks.json, and Windows ships no system timezone database, so `tzdata` is
REM  required too - without it the evaluator raises ZoneInfoNotFoundError before
REM  it scores anything. tzdata is not listed in the submodule's
REM  requirements.txt because evaluation has only ever been run on Linux.
REM
REM  The rest of requirements.txt (openai, pyyaml) belongs to the data
REM  generation scripts and is deliberately not installed here - pinning those
REM  could clobber unrelated packages on the host.
REM ---------------------------------------------------------------------------

:python_deps
echo.
echo [2/5] Python packages for the evaluation script...

call :check_python_deps
if not errorlevel 1 (
    echo   regex and tzdata are already installed.
    goto :model
)

echo   installing regex and tzdata with %PYTHON_CMD%...
%PYTHON_CMD% -m pip install --user --quiet regex tzdata
if errorlevel 1 (
    REM --user is rejected inside a virtualenv, and in a few other setups.
    echo   retrying without --user...
    %PYTHON_CMD% -m pip install --quiet regex tzdata
)

call :check_python_deps
if errorlevel 1 (
    echo   [ERROR] Could not install the evaluation script's dependencies.
    echo           Install them manually and re-run:
    echo             %PYTHON_CMD% -m pip install regex tzdata
    goto :fail
)
echo   ok   regex, tzdata

REM ---------------------------------------------------------------------------
REM  Step 3 - Application behavioural model (Zenodo)
REM
REM  %MODEL_ARCHIVE% holds "odobot.db" and the "graphdb" directory at its root,
REM  so it unpacks directly into .\db. The download is verified against
REM  %MODEL_ARCHIVE_MD5% before extraction.
REM
REM  Only OdoBot needs this. Agent-E and WebVoyager drive Canvas through the
REM  browser and never read the behavioural model, so a failure here does not
REM  block the baseline runs.
REM ---------------------------------------------------------------------------

:model
echo.
echo [3/5] Application behavioural model...

if not exist "db" mkdir "db"

if exist "db\odobot.db" if exist "db\graphdb" (
    echo   db\odobot.db and db\graphdb already exist - skipping.
    echo   Delete them to force a re-download/re-extraction.
    goto :images
)

if not exist "db\%MODEL_ARCHIVE%" (
    echo   Downloading %MODEL_ARCHIVE% ^(~290 MB, this will take a while^)...
    echo   %ZENODO_URL%

    where curl.exe >nul 2>&1
    if errorlevel 1 (
        set "ODO_SETUP_STEP=download"
        call :run_powershell
        if errorlevel 1 goto :fail
    ) else (
        curl.exe -L --fail --progress-bar -o "db\%MODEL_ARCHIVE%" "%ZENODO_URL%"
        if errorlevel 1 (
            echo   [ERROR] Download failed.
            if exist "db\%MODEL_ARCHIVE%" del /f /q "db\%MODEL_ARCHIVE%"
            goto :fail
        )
    )
) else (
    echo   Using existing db\%MODEL_ARCHIVE%.
)

echo   Extracting into .\db ^(this can take several minutes^)...
set "ODO_SETUP_STEP=extract"
call :run_powershell
if errorlevel 1 goto :fail

REM ---------------------------------------------------------------------------
REM  Step 4 - docker images
REM ---------------------------------------------------------------------------

:images
echo.
echo [4/5] Pulling docker images...

echo   %ODOBOT_IMAGE%
docker pull %ODOBOT_IMAGE%
if errorlevel 1 (
    echo   [ERROR] docker pull failed for %ODOBOT_IMAGE%. Is the Docker daemon running?
    goto :fail
)

echo   %CANVAS_IMAGE%
docker pull %CANVAS_IMAGE%
if errorlevel 1 (
    echo   [ERROR] docker pull failed for %CANVAS_IMAGE%.
    goto :fail
)

echo   %AGENTE_IMAGE%
docker pull %AGENTE_IMAGE%
if errorlevel 1 (
    echo   [ERROR] docker pull failed for %AGENTE_IMAGE%.
    goto :fail
)

echo   %WEBVOYAGER_IMAGE%
docker pull %WEBVOYAGER_IMAGE%
if errorlevel 1 (
    echo   [ERROR] docker pull failed for %WEBVOYAGER_IMAGE%.
    goto :fail
)

echo   %SELENIUM_IMAGE%
docker pull %SELENIUM_IMAGE%
if errorlevel 1 (
    echo   [ERROR] docker pull failed for %SELENIUM_IMAGE%.
    goto :fail
)

REM ---------------------------------------------------------------------------
REM  Step 5 - Configuration files
REM
REM  Every config\*-example.yaml is copied to config\*.yaml with the "-example"
REM  suffix stripped, and any "secretKey:" / "model:" value is replaced with
REM  OPENAI_API_KEY / OPENAI_MODEL.
REM
REM  config\main.yaml is also patched: its "modelOverride" key takes precedence
REM  over the per-service "model" values (see MainVerticle.java), so it has to
REM  agree with OPENAI_MODEL or the per-service values would be ignored.
REM
REM  agents_llm_config.json is written for Agent-E, which reads its model name
REM  and API key only from that file - never from the environment.
REM ---------------------------------------------------------------------------

echo.
echo [5/5] Generating config files...

set "ODO_SETUP_STEP=configs"
call :run_powershell
if errorlevel 1 goto :fail

set "ODO_SETUP_STEP=llmconfig"
call :run_powershell
if errorlevel 1 goto :fail

echo.
echo Setup complete.
echo   Model      -^> .\db
echo   Evaluation -^> .\%EVAL_SUBMODULE%
echo   Python     -^> %PYTHON_CMD% ^(regex, tzdata^)
echo   OdoBot     -^> %ODOBOT_IMAGE%
echo   Canvas     -^> %CANVAS_IMAGE%
echo   Agent-E    -^> %AGENTE_IMAGE%
echo   WebVoyager -^> %WEBVOYAGER_IMAGE%
echo   Browser    -^> %SELENIUM_IMAGE%
echo   Config     -^> .\config\*.yaml, .\agents_llm_config.json
REM Smoke tests first: a full instance takes hours per agent, so it is worth
REM spending a few minutes proving the plumbing before committing to one. The
REM `if exist` guards keep this honest - cascon-experiment-smoke-test.json is not
REM tracked in the repository, so it is absent from a fresh clone.
echo.
echo Smoke test each agent first ^(2 tasks each, minutes rather than hours^):
if exist "cascon-experiment-smoke-test.json"            echo   cascon-experiment.bat cascon-experiment-smoke-test.json 1
if exist "cascon-experiment-agent-e-smoke-test.json"    echo   cascon-experiment.bat cascon-experiment-agent-e-smoke-test.json 1 --agent agent-e
if exist "cascon-experiment-webvoyager-smoke-test.jsonl" echo   cascon-experiment.bat cascon-experiment-webvoyager-smoke-test.jsonl 1 --agent webvoyager
echo.
echo Then run the full experiment ^(45 tasks, hours per instance^):
if exist "cascon-experiment.json"            echo   cascon-experiment.bat cascon-experiment.json 5
if exist "cascon-experiment-agent-e.json"    echo   cascon-experiment.bat cascon-experiment-agent-e.json 5 --agent agent-e
if exist "cascon-experiment-webvoyager.jsonl" echo   cascon-experiment.bat cascon-experiment-webvoyager.jsonl 5 --agent webvoyager
echo.
echo All three agents run the same 45 task instances and write their evaluation
echo report to execution_events\^<experimentId^>\results\, so runs are comparable.
echo.

popd
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
REM  Helpers
REM ---------------------------------------------------------------------------

:find_python
REM Try python3 first, then python, then the py launcher. Each candidate is
REM validated by actually running it: on Windows both python.exe and python3.exe
REM exist under WindowsApps as Microsoft Store stubs that resolve on PATH but do
REM not run, so `where` is not a reliable test.
REM cascon-experiment.bat repeats this routine, so the two scripts always agree
REM about which interpreter has the dependencies.
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

:report_python
for /f "usebackq tokens=*" %%V in (`%PYTHON_CMD% -c "import sys; print('.'.join(map(str, sys.version_info[:3])))" 2^>nul`) do set "PYTHON_VERSION=%%V"
echo   python         = %PYTHON_CMD% ^(%PYTHON_VERSION%^)
exit /b 0

:check_python_deps
REM Exercise the real thing rather than a bare import: on Windows ZoneInfo only
REM works once the tzdata package is present.
%PYTHON_CMD% -c "import regex; from zoneinfo import ZoneInfo; ZoneInfo('Canada/Mountain')" >nul 2>&1
exit /b %errorlevel%

:run_powershell
REM Runs the PowerShell block at the bottom of this file. The step to execute is
REM passed via ODO_SETUP_STEP; the API key is never placed on a command line.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$m = '#PS' + '_TAIL#'; $b = [IO.File]::ReadAllText($env:SCRIPT_PATH); $i = $b.IndexOf($m); if ($i -lt 0) { Write-Host '[ERROR] PowerShell payload not found in script.'; exit 1 }; Invoke-Expression $b.Substring($i + $m.Length)"
exit /b %errorlevel%

:fail
echo.
echo Setup aborted.
popd
endlocal
exit /b 1

#PS_TAIL#
$ErrorActionPreference = 'Stop'

# Written without a BOM: the YAML parser on the Java side does not strip one.
function Write-TextFile($path, $lines) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines($path, [string[]]$lines, $utf8NoBom)
}

try {
    $root = $env:ODO_SETUP_ROOT
    $db = Join-Path $root 'db'
    $archive = Join-Path $db $env:MODEL_ARCHIVE

    switch ($env:ODO_SETUP_STEP) {

        'download' {
            # Only reached when curl.exe is unavailable; the batch side prefers
            # curl because it can show a progress bar for a download this size.
            $ProgressPreference = 'SilentlyContinue'
            try {
                Invoke-WebRequest -Uri $env:ZENODO_URL -OutFile $archive -UseBasicParsing
            } catch {
                if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
                throw
            }
        }

        'extract' {
            # Verify before extracting. A truncated download is the likely failure
            # for a ~290 MB transfer, and it surfaces as a confusing "End of
            # Central Directory record could not be found" rather than an
            # obviously incomplete file. This also catches a stale or partial
            # archive left in .\db by an earlier interrupted run.
            if ($env:MODEL_ARCHIVE_SIZE) {
                $actualSize = (Get-Item -LiteralPath $archive).Length
                if ($actualSize -ne [long]$env:MODEL_ARCHIVE_SIZE) {
                    throw ("{0} is {1:N0} bytes, expected {2:N0}. The download is incomplete - delete it and re-run this script." -f $env:MODEL_ARCHIVE, $actualSize, [long]$env:MODEL_ARCHIVE_SIZE)
                }
            }
            if ($env:MODEL_ARCHIVE_MD5) {
                Write-Host '  Verifying checksum...'
                $actualMd5 = (Get-FileHash -LiteralPath $archive -Algorithm MD5).Hash.ToLower()
                if ($actualMd5 -ne $env:MODEL_ARCHIVE_MD5.ToLower()) {
                    throw ("Checksum mismatch for {0}.`n           expected md5 {1}`n           got      md5 {2}`n           Delete .\db\{0} and re-run this script." -f $env:MODEL_ARCHIVE, $env:MODEL_ARCHIVE_MD5, $actualMd5)
                }
                Write-Host ("  ok   md5 {0}" -f $actualMd5)
            }

            # Expand-Archive is slow on a multi-hundred-MB archive but is always
            # available; `tar -xf <zip> -C db` is a faster alternative on
            # Windows 10 1803+.
            Expand-Archive -LiteralPath $archive -DestinationPath $db -Force

            $missing = $false
            foreach ($expected in @('odobot.db', 'graphdb')) {
                $p = Join-Path $db $expected
                if (Test-Path -LiteralPath $p) {
                    Write-Host ("  ok   db\{0}" -f $expected)
                } else {
                    Write-Host ("  [WARN] db\{0} not found after extraction - check the archive layout." -f $expected)
                    $missing = $true
                }
            }
            if ($missing) {
                throw "The archive did not unpack into the expected layout. cascon-experiment.bat requires db\odobot.db and db\graphdb to run OdoBot."
            }
        }

        'configs' {
            $q = [char]34
            $cfg = Join-Path $root 'config'
            $key = $env:OPENAI_API_KEY
            $model = $env:OPENAI_MODEL

            $examples = @(Get-ChildItem -LiteralPath $cfg -Filter '*-example.yaml' -File)
            if ($examples.Count -eq 0) {
                throw "No *-example.yaml files found in $cfg"
            }

            foreach ($example in $examples) {
                $targetName = $example.Name -replace '-example\.yaml$', '.yaml'
                $target = Join-Path $cfg $targetName

                # `secretKey:` is the OpenAI key in both the openAI and
                # sqliteVectorConfig blocks. `model:` is anchored after the
                # indent so `embeddingModel:` is left alone.
                $out = foreach ($line in (Get-Content -LiteralPath $example.FullName)) {
                    if ($line -match '^(\s*)secretKey:') {
                        '{0}secretKey: {1}{2}{1}' -f $matches[1], $q, $key
                    } elseif ($line -match '^(\s*)model:') {
                        '{0}model: {1}{2}{1}' -f $matches[1], $q, $model
                    } else {
                        $line
                    }
                }

                Write-TextFile $target $out
                Write-Host ("  {0} -> {1}" -f $example.Name, $targetName)
            }

            # modelOverride in main.yaml wins over every per-service model.
            $mainPath = Join-Path $cfg 'main.yaml'
            if (Test-Path -LiteralPath $mainPath) {
                $mainLines = @(Get-Content -LiteralPath $mainPath)
                $overrideLine = 'modelOverride: {0}{1}{0}' -f $q, $model
                if ($mainLines -match '^\s*modelOverride\s*:') {
                    $mainLines = $mainLines | ForEach-Object {
                        if ($_ -match '^(\s*)modelOverride\s*:') { '{0}modelOverride: {1}{2}{1}' -f $matches[1], $q, $model } else { $_ }
                    }
                } else {
                    $mainLines += $overrideLine
                }
                Write-TextFile $mainPath $mainLines
                Write-Host ("  main.yaml modelOverride -> {0}" -f $model)
            } else {
                Write-Host '  [WARN] config\main.yaml not found - modelOverride not set.'
            }
        }

        'llmconfig' {
            # Agent-E reads the model name and API key only from this file, never
            # from the environment (AgentsLLMConfig maps model_api_key -> api_key
            # into autogen's config_list), and its entrypoint exits 78 without it.
            #
            # The parameters match the configuration the CASCON 2026 Agent-E
            # results were produced with: temperature 1, seed 12345, no top_p.
            # The upstream agents_llm_config-example.json instead uses
            # temperature 0.0 / top_p 0.001, which current reasoning models reject.
            #
            # cascon-experiment.bat regenerates this file on every Agent-E run, so
            # changing OPENAI_MODEL moves Agent-E and WebVoyager together.
            $path = Join-Path $root 'agents_llm_config.json'
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
            # The stanza key must match the image's baked
            # AGENTS_LLM_CONFIG_FILE_REF_KEY, which is "openai_gpt".
            $cfg = [ordered]@{
                openai_gpt = [ordered]@{
                    planner_agent     = $agentCfg
                    browser_nav_agent = $agentCfg
                }
            }
            $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
            [IO.File]::WriteAllText($path, ($cfg | ConvertTo-Json -Depth 6), $utf8NoBom)
            Write-Host ("  agents_llm_config.json -> model {0}" -f $env:OPENAI_MODEL)
        }

        default {
            throw "Unknown setup step: '$($env:ODO_SETUP_STEP)'"
        }
    }
} catch {
    Write-Host ('  [ERROR] ' + $_.Exception.Message)
    exit 1
}

exit 0
