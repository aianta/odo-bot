@echo off
setlocal EnableExtensions
REM ============================================================================
REM  cascon-environment-setup.bat
REM
REM  Prepares a Windows machine to reproduce the CASCON 2026 experiment:
REM    1. Downloads the Canvas LMS application behavioural model from Zenodo
REM       and unpacks it into .\db
REM    2. Pulls the odobot, canvas-bench, agent-e-cascon, webvoyager-cascon
REM       and selenium/standalone-firefox docker images
REM    3. Generates config\*.yaml from config\*-example.yaml, injecting
REM       %OPENAI_API_KEY% and %OPENAI_MODEL%
REM
REM  Requires: docker, PowerShell 5.1+ (ships with Windows 10/11)
REM ============================================================================

REM %0 refers to the label inside a `call :label`, so capture the path up front.
set "SCRIPT_PATH=%~f0"
pushd "%~dp0"
set "ODO_SETUP_ROOT=%CD%"

REM ---------------------------------------------------------------------------
REM  Configuration
REM ---------------------------------------------------------------------------

REM TODO: replace with the real Zenodo record id once the artifact is published.
set "ZENODO_RECORD_ID=REPLACE_WITH_ZENODO_RECORD_ID"
set "MODEL_ARCHIVE=odobot-model.zip"
set "ZENODO_URL=https://zenodo.org/records/%ZENODO_RECORD_ID%/files/%MODEL_ARCHIVE%?download=1"

REM OdoBot itself. ":latest" is a moving tag - consider pushing and pinning a dated or
REM versioned tag (as canvas-bench does with :cascon-2026) for the archival artifact.
REM
REM NOTE: pulling this replaces whatever "aianta/odobot:latest" refers to locally. If you
REM have built the image yourself (docker build -t aianta/odobot -f docker-gradle/Dockerfile .)
REM your local build loses the tag and is left dangling.
set "ODOBOT_IMAGE=aianta/odobot:latest"

set "CANVAS_IMAGE=aianta/canvas-bench:cascon-2026"

REM Agent-E, the baseline web agent that was evaluated on the same Canvas tasks as OdoBot
REM as part of this work. It is not launched by cascon-experiment.bat - it is run separately
REM - but the image is pulled here so the full evaluation can be reproduced from one setup
REM step.
REM
REM ":latest" is a moving tag. As of 2026-09-10 it resolves to
REM   sha256:8579c7e5ee815c0231d1c1d1129fbf07244bbb669fd2f251cb3e44410ba09af0
REM which is the image the CASCON 2026 Agent-E results were produced with. Pin the digest
REM here instead if the tag moves.
set "AGENTE_IMAGE=aianta/agent-e-cascon:latest"

REM WebVoyager, the other baseline web agent evaluated on the same Canvas tasks as
REM OdoBot. Like Agent-E it is not launched by cascon-experiment.bat - it is run
REM separately (see run_experiment_webvoyager.sh) - but the image is pulled here so the
REM full evaluation can be reproduced from one setup step.
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
echo [0/3] Checking environment...

set "PREFLIGHT_FAILED="

if not defined OPENAI_API_KEY (
    echo   [ERROR] OPENAI_API_KEY is not set.
    echo           Set it with:  setx OPENAI_API_KEY "sk-..."
    echo           then open a new terminal and re-run this script.
    set "PREFLIGHT_FAILED=1"
)

if not defined OPENAI_MODEL (
    echo   [ERROR] OPENAI_MODEL is not set.
    echo           Set it with:  setx OPENAI_MODEL "gpt-5-mini-2025-08-07"
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

if defined PREFLIGHT_FAILED goto :fail

echo   OPENAI_MODEL   = %OPENAI_MODEL%
echo   OPENAI_API_KEY = ^(set^)
echo   docker         = ok

REM ---------------------------------------------------------------------------
REM  Step 1 - Application behavioural model (Zenodo)
REM
REM  *** DRAFT - UNTESTED ***
REM  The Zenodo artifact has not been uploaded yet, so ZENODO_RECORD_ID above is
REM  still a placeholder and this step has never been run end to end. Once the
REM  record exists, set ZENODO_RECORD_ID and verify the download + extraction.
REM
REM  %MODEL_ARCHIVE% is expected to contain "odobot.db" and the "graphdb"
REM  directory at its root, so it unpacks directly into .\db. If the archive
REM  ends up with a top level folder instead, extract to a temp dir and move the
REM  contents into .\db.
REM ---------------------------------------------------------------------------

echo.
echo [1/3] Application behavioural model...

if not exist "db" mkdir "db"

if exist "db\odobot.db" if exist "db\graphdb" (
    echo   db\odobot.db and db\graphdb already exist - skipping.
    echo   Delete them to force a re-download/re-extraction.
    goto :canvas_image
)

if not exist "db\%MODEL_ARCHIVE%" (
    if "%ZENODO_RECORD_ID%"=="REPLACE_WITH_ZENODO_RECORD_ID" (
        echo   [WARN] The Zenodo record id has not been filled in yet, and
        echo          db\%MODEL_ARCHIVE% is not present locally.
        echo          Skipping the model download - edit ZENODO_RECORD_ID in this
        echo          script, or drop %MODEL_ARCHIVE% into .\db manually.
        goto :canvas_image
    )

    echo   Downloading %MODEL_ARCHIVE% ^(several hundred MB, this will take a while^)...
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
REM  Step 2 - docker images
REM ---------------------------------------------------------------------------

:canvas_image
echo.
echo [2/3] Pulling docker images...

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
REM  Step 3 - Configuration files
REM
REM  Every config\*-example.yaml is copied to config\*.yaml with the "-example"
REM  suffix stripped, and any "secretKey:" / "model:" value is replaced with
REM  OPENAI_API_KEY / OPENAI_MODEL.
REM
REM  config\main.yaml is also patched: its "modelOverride" key takes precedence
REM  over the per-service "model" values (see MainVerticle.java), so it has to
REM  agree with OPENAI_MODEL or the per-service values would be ignored.
REM ---------------------------------------------------------------------------

echo.
echo [3/3] Generating config files...

set "ODO_SETUP_STEP=configs"
call :run_powershell
if errorlevel 1 goto :fail

echo.
echo Setup complete.
echo   Model      -^> .\db
echo   OdoBot     -^> %ODOBOT_IMAGE%
echo   Canvas     -^> %CANVAS_IMAGE%
echo   Agent-E    -^> %AGENTE_IMAGE%
echo   WebVoyager -^> %WEBVOYAGER_IMAGE%
echo   Browser    -^> %SELENIUM_IMAGE%
echo   Config     -^> .\config\*.yaml
echo.
echo Start the Canvas environment with:
echo   docker rm -f canvas ^&^& docker run -d --name canvas -p 8088:80 %CANVAS_IMAGE%
echo.
echo Start the browser grid with ^(MOZ_REMOTE_ALLOW_SYSTEM_ACCESS is required^):
echo   docker rm -f selenium-firefox ^&^& docker run -d --name selenium-firefox --shm-size=2g -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -p 4444:4444 -p 7900:7900 %SELENIUM_IMAGE%
echo.

popd
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
REM  Helpers
REM ---------------------------------------------------------------------------

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
    $archive = Join-Path $db 'odobot-model.zip'

    switch ($env:ODO_SETUP_STEP) {

        # ---- DRAFT: not exercised yet, the Zenodo record does not exist ------
        'download' {
            $ProgressPreference = 'SilentlyContinue'
            try {
                Invoke-WebRequest -Uri $env:ZENODO_URL -OutFile $archive -UseBasicParsing
            } catch {
                if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
                throw
            }
        }

        # ---- DRAFT: the real archive has not been published yet --------------
        'extract' {
            # Expand-Archive is slow on a multi-hundred-MB archive but is always
            # available; `tar -xf <zip> -C db` is a faster alternative on
            # Windows 10 1803+.
            Expand-Archive -LiteralPath $archive -DestinationPath $db -Force

            foreach ($expected in @('odobot.db', 'graphdb')) {
                $p = Join-Path $db $expected
                if (Test-Path -LiteralPath $p) {
                    Write-Host ("  ok   db\{0}" -f $expected)
                } else {
                    Write-Host ("  [WARN] db\{0} not found after extraction - check the archive layout." -f $expected)
                }
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

        default {
            throw "Unknown setup step: '$($env:ODO_SETUP_STEP)'"
        }
    }
} catch {
    Write-Host ('  [ERROR] ' + $_.Exception.Message)
    exit 1
}

exit 0
