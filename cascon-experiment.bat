@echo off
setlocal EnableExtensions
REM ============================================================================
REM  cascon-experiment.bat <EXPERIMENT DEFINITION FILE> <NUM INSTANCES>
REM
REM  Runs the CASCON 2026 experiment. For each instance it:
REM    1. Resets the environment (recreates the Canvas, browser and OdoBot
REM       containers, so every instance starts from identical state)
REM    2. Builds a per-instance copy of the experiment definition with
REM       "-<INSTANCE>" appended to experimentId and the three inter-container
REM       addresses rewritten to the running containers' addresses
REM    3. POSTs it to OdoBot and waits for the run to finish
REM
REM  The experiment definition file on disk is never modified; the per-instance
REM  copy is written to %TEMP%.
REM
REM  Requires: docker, PowerShell 5.1+, and a completed cascon-environment-setup.
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

REM All three containers share a user-defined bridge network so they can reach
REM each other directly. Addresses on this network are what get written into the
REM per-instance experiment definition.
set "NETWORK=odobot-net"

set "ODOBOT_NAME=odobot"
set "CANVAS_NAME=canvas"
set "SELENIUM_NAME=selenium-firefox"

set "AGENT=odoBotNL"

REM Where the generated per-instance definitions go, and the file the PowerShell
REM step uses to hand the generated path back to this script.
set "INSTANCE_DIR=%TEMP%\odobot-experiments"
set "ODO_EXP_RESULT=%INSTANCE_DIR%\current-instance.path"
set "ODO_EXP_LOGPID=%INSTANCE_DIR%\current-logtail.pid"

REM Whether to capture the OdoBot container's output into each experiment's
REM logs\ folder. OdoBot is very chatty (~500 KB for a 3-task run), so this can
REM be turned off. Override with the ODOBOT_CAPTURE_LOGS environment variable, or
REM per-run with the --logs / --no-logs argument.
REM When off, a failing instance still gets its log written, so there is always
REM something to diagnose a failure with.
set "CAPTURE_ODOBOT_LOGS=1"
if defined ODOBOT_CAPTURE_LOGS set "CAPTURE_ODOBOT_LOGS=%ODOBOT_CAPTURE_LOGS%"

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
set "LOGS_ARG=%~3"

if "%EXPERIMENT_FILE%"=="" goto :usage
if "%NUM_INSTANCES%"=="" goto :usage

if not "%LOGS_ARG%"=="" (
    if /i "%LOGS_ARG%"=="--no-logs" (
        set "CAPTURE_ODOBOT_LOGS=0"
    ) else if /i "%LOGS_ARG%"=="--logs" (
        set "CAPTURE_ODOBOT_LOGS=1"
    ) else (
        echo [ERROR] Unknown option: %LOGS_ARG%
        goto :usage
    )
)

if not exist "%EXPERIMENT_FILE%" (
    echo [ERROR] Experiment definition file not found: %EXPERIMENT_FILE%
    goto :fail
)

echo %NUM_INSTANCES%| findstr /r /c:"^[1-9][0-9]*$" >nul
if errorlevel 1 (
    echo [ERROR] NUM INSTANCES must be a positive integer, got: %NUM_INSTANCES%
    goto :fail
)

set "EXPERIMENT_FILE_ABS=%~f1"

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

if not exist "config\explorer.yaml" (
    echo [ERROR] config\explorer.yaml is missing. Run cascon-environment-setup.bat first.
    set "PREFLIGHT_FAILED=1"
)

if not exist "db\odobot.db" (
    echo [ERROR] db\odobot.db is missing. Run cascon-environment-setup.bat first.
    set "PREFLIGHT_FAILED=1"
)

if not exist "execution_events" mkdir "execution_events"
if not exist "libs" mkdir "libs"

if defined PREFLIGHT_FAILED goto :fail

echo.
echo ============================================================
echo  Experiment definition : %EXPERIMENT_FILE%
echo  Instances             : %NUM_INSTANCES%
echo  Agent                 : %AGENT%
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
if "%CAPTURE_ODOBOT_LOGS%"=="1" echo Each instance's OdoBot log is in its logs\ subfolder.
echo.
echo The containers are left running so the final state can be inspected
echo ^(Canvas on http://localhost:%CANVAS_PORT%, browser view on http://localhost:%SELENIUM_VNC_PORT%^).
echo Stop them with:
echo   docker rm -f %ODOBOT_NAME% %CANVAS_NAME% %SELENIUM_NAME%
echo.

popd
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
REM  One experiment instance
REM ---------------------------------------------------------------------------

:run_instance
set "INSTANCE=%~1"

echo.
echo ============================================================
echo  Instance %INSTANCE% of %NUM_INSTANCES%
echo ============================================================

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

REM The PowerShell step reports back as key=value lines.
set "INSTANCE_FILE="
set "FULL_EXPERIMENT_ID="
set "BASE_EXPERIMENT_ID="
for /f "usebackq tokens=1,* delims==" %%A in ("%ODO_EXP_RESULT%") do (
    if "%%A"=="path" set "INSTANCE_FILE=%%B"
    if "%%A"=="id"   set "FULL_EXPERIMENT_ID=%%B"
    if "%%A"=="base" set "BASE_EXPERIMENT_ID=%%B"
)
if not defined INSTANCE_FILE (
    echo   [ERROR] Failed to determine the generated experiment definition path.
    exit /b 1
)
if not defined FULL_EXPERIMENT_ID (
    echo   [ERROR] Failed to determine the experiment id for this instance.
    exit /b 1
)

set "ARTIFACT_DIR=execution_events\%FULL_EXPERIMENT_ID%"
set "LOG_DIR=%ARTIFACT_DIR%\logs"
set "ODOBOT_LOG=%LOG_DIR%\odobot.log"

REM Stream the OdoBot container's output into the experiment's own log folder for
REM the duration of the run, unless log capture is turned off.
if "%CAPTURE_ODOBOT_LOGS%"=="1" (
    if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
    set "ODO_EXP_STEP=logstart"
    call :run_powershell
    if errorlevel 1 exit /b 1
)

echo.
echo [4/4] Submitting experiment to OdoBot and waiting for completion...
echo   POST http://localhost:%ODOBOT_API_PORT%/api/evaluate?agent=%AGENT%
echo   ^(this runs every task in the definition and can take a long time^)
echo.
echo   Watch it live in the browser : http://localhost:%SELENIUM_VNC_PORT%   ^(password: secret^)
if "%CAPTURE_ODOBOT_LOGS%"=="1" (
    echo   OdoBot log                   : %ODOBOT_LOG%
) else (
    echo   OdoBot log                   : capture off ^(pass --logs to enable^)
)
echo   Artifacts                    : %ARTIFACT_DIR%\
echo.

REM curl exits 0 on an HTTP error, so the status code is captured separately and
REM checked below - otherwise a failed experiment looks like a successful one.
set "RESP_FILE=%INSTANCE_DIR%\current-response.txt"
set "CODE_FILE=%INSTANCE_DIR%\current-httpcode.txt"

curl.exe -s -S -X POST "http://localhost:%ODOBOT_API_PORT%/api/evaluate?agent=%AGENT%" ^
    -H "Content-Type: application/json" ^
    --data-binary "@%INSTANCE_FILE%" ^
    -o "%RESP_FILE%" ^
    -w "%%{http_code}" > "%CODE_FILE%"
set "CURL_EXIT=%errorlevel%"

set "HTTP_CODE="
if exist "%CODE_FILE%" set /p HTTP_CODE=<"%CODE_FILE%"

REM Stop the tail and replace the file with a complete dump, so the log is whole
REM even if the stream was interrupted.
if "%CAPTURE_ODOBOT_LOGS%"=="1" (
    set "ODO_EXP_STEP=logstop"
    call :run_powershell
)

if not "%CURL_EXIT%"=="0" (
    echo   [ERROR] Could not reach OdoBot ^(curl exit %CURL_EXIT%^).
    echo   See %ODOBOT_LOG%
    exit /b 1
)

if not "%HTTP_CODE%"=="200" (
    echo.
    echo   [ERROR] OdoBot returned HTTP %HTTP_CODE% - the experiment did not complete.
    if exist "%RESP_FILE%" type "%RESP_FILE%"
    echo.
    REM Even with capture off, write the log for a failed instance - otherwise there
    REM would be nothing to diagnose the failure with.
    if not "%CAPTURE_ODOBOT_LOGS%"=="1" (
        if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
        set "ODO_EXP_STEP=logstop"
        call :run_powershell
    )
    echo   Note: tasks may have executed before the failure. Check the log for the
    echo   cause, then inspect %ARTIFACT_DIR%\results\ for evaluation output.
    echo   Full log: %ODOBOT_LOG%
    exit /b 1
)

echo   HTTP %HTTP_CODE%
echo.
echo Instance %INSTANCE% finished.
echo   Artifacts : %ARTIFACT_DIR%\
if "%CAPTURE_ODOBOT_LOGS%"=="1" echo   OdoBot log: %ODOBOT_LOG%
exit /b 0

REM ---------------------------------------------------------------------------
REM  Environment reset - every instance starts from identical container state
REM ---------------------------------------------------------------------------

:reset_environment

echo.
echo [1/4] Resetting environment...

docker rm -f %ODOBOT_NAME% %CANVAS_NAME% %SELENIUM_NAME% >nul 2>&1

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

echo.
echo [2/4] Waiting for services...
set "ODO_EXP_STEP=wait"
call :run_powershell
if errorlevel 1 exit /b 1

exit /b 0

REM ---------------------------------------------------------------------------
REM  Helpers
REM ---------------------------------------------------------------------------

:run_powershell
REM Runs the PowerShell block at the bottom of this file, selected by ODO_EXP_STEP.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$m = '#PS' + '_TAIL#'; $b = [IO.File]::ReadAllText($env:SCRIPT_PATH); $i = $b.IndexOf($m); if ($i -lt 0) { Write-Host '[ERROR] PowerShell payload not found in script.'; exit 1 }; Invoke-Expression $b.Substring($i + $m.Length)"
exit /b %errorlevel%

:usage
echo.
echo Usage: cascon-experiment.bat ^<EXPERIMENT DEFINITION FILE^> ^<NUM INSTANCES^> [--logs^|--no-logs]
echo.
echo   EXPERIMENT DEFINITION FILE  The tasks to run. Use cascon-experiment.json for
echo                               the full CASCON 2026 experiment, or
echo                               cascon-experiment-smoke-test.json for a 3-task
echo                               smoke test.
echo   NUM INSTANCES               How many times to repeat the experiment. The
echo                               environment is reset between instances.
echo   --logs ^| --no-logs          Capture the OdoBot container's output into
echo                               execution_events\^<experimentId^>\logs\odobot.log.
echo                               On by default; OdoBot is chatty, so --no-logs
echo                               skips it. A failing instance is logged either way.
echo                               The ODOBOT_CAPTURE_LOGS environment variable
echo                               ^(1 or 0^) sets the default.
echo.
echo Examples:
echo   cascon-experiment.bat cascon-experiment.json 5
echo   cascon-experiment.bat cascon-experiment-smoke-test.json 1 --no-logs
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

function Get-JsonStringField($json, $field) {
    $pattern = '"' + [regex]::Escape($field) + '"\s*:\s*"((?:[^"\\]|\\.)*)"'
    $m = [regex]::Match($json, $pattern)
    if (-not $m.Success) { throw "Field '$field' not found in the experiment definition." }
    return $m.Groups[1].Value
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

            Wait-Until "$($env:SELENIUM_NAME) grid ready" 240 {
                $s = Invoke-RestMethod "http://localhost:$($env:SELENIUM_PORT)/status" -TimeoutSec 5
                return [bool]$s.value.ready
            }

            # The published port answers as soon as the container starts, well before
            # the server binds, so key off the line OdoBot logs once the evaluate
            # endpoint is actually listening.
            Wait-Until "$($env:ODOBOT_NAME) API listening" 900 {
                $logs = Get-ContainerLogs $env:ODOBOT_NAME
                if ($logs -match 'Data Generation \(Explorer\) Service service started on port') { return $true }
                $state = (docker inspect -f "{{.State.Running}}" $env:ODOBOT_NAME | Out-String).Trim()
                if ($state -ne 'true') { throw "FATAL: the $($env:ODOBOT_NAME) container exited before becoming ready. Check: docker logs $($env:ODOBOT_NAME)" }
                return $false
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

            $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
            [IO.File]::WriteAllText($outFile, $json, $utf8NoBom)

            Write-Host ("  experimentId         {0}" -f $experimentId)
            Write-Host ("  firefoxDockerGridURL http://{0}:{1}" -f $seleniumAddr, $env:SELENIUM_PORT)
            Write-Host ("  guidanceServiceHost  {0}:{1}" -f $odobotAddr, $env:ODOBOT_GUIDANCE_PORT)
            Write-Host ("  webAppURL            http://{0}{1}" -f $canvasAddr, $path)
            Write-Host ("  definition           {0}" -f $outFile)

            # Hand the results back to the batch script. An environment variable
            # would not survive the child process, so they go through a file.
            $result = @(
                "path=$outFile",
                "id=$experimentId",
                "base=$baseId"
            )
            [IO.File]::WriteAllLines($env:ODO_EXP_RESULT, [string[]]$result, $utf8NoBom)
        }

        'logstart' {
            # Tail the container into the experiment's log folder. cmd does the
            # stream merge so stdout and stderr land in one file in order.
            $logFile = Join-Path $env:PROJECT_DIR $env:ODOBOT_LOG
            $cmdLine = 'docker logs -f {0} > "{1}" 2>&1' -f $env:ODOBOT_NAME, $logFile
            $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmdLine -PassThru -NoNewWindow
            [IO.File]::WriteAllText($env:ODO_EXP_LOGPID, $proc.Id.ToString(), (New-Object System.Text.UTF8Encoding($false)))
            Write-Host ("  streaming {0} logs -> {1}" -f $env:ODOBOT_NAME, $env:ODOBOT_LOG)
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
            $logFile = Join-Path $env:PROJECT_DIR $env:ODOBOT_LOG
            cmd /c "docker logs $($env:ODOBOT_NAME) > `"$logFile`" 2>&1"
            $size = 0
            if (Test-Path -LiteralPath $logFile) { $size = (Get-Item -LiteralPath $logFile).Length }
            Write-Host ("  OdoBot log written ({0:N0} bytes)" -f $size)
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