<#
.SYNOPSIS
    Launch, drive and tear down N independent OdoBot + Firefox pairs on odobot-net, so that N evaluate requests can
    run at the same time. One OdoBot process can only run one task at a time, see SKILL.md.

.EXAMPLE
    # Start 2 pairs from the locally built image
    pwsh -File odobot-instances.ps1 -Action up -Count 2

    # Make one task file per pair from a template, point each at its own environment, and submit them concurrently
    pwsh -File odobot-instances.ps1 -Action submit -Count 2 -Template time-logging-test.json `
        -WebAppUrls http://172.23.0.2/admin,http://172.23.0.5/admin

    # Show what is running, then remove the pairs
    pwsh -File odobot-instances.ps1 -Action status -Count 2
    pwsh -File odobot-instances.ps1 -Action down -Count 2
#>
param(
    [Parameter(Mandatory)][ValidateSet('up', 'status', 'submit', 'down')][string]$Action,
    [Parameter(Mandatory)][ValidateRange(1, 50)][int]$Count,
    # The first instance acted on: the action covers instances Start .. Start+Count-1. Use it to send different
    # experiments to different instances, e.g. -Start 1 -Count 1 for one, -Start 2 -Count 1 for another.
    [ValidateRange(1, 50)][int]$Start = 1,
    [string]$Image = 'aianta/odobot:latest',
    [string]$Network = 'odobot-net',
    # submit: the task file every instance's file is made from.
    [string]$Template,
    # submit: one web app URL per instance, replacing the template's webAppURL (and the matching origin in userLocation
    # and targetHosts). Without it every instance targets the template's environment, see SKILL.md.
    [string[]]$WebAppUrls,
    # up: web app containers (one per instance, e.g. env1-shopping_admin-1,env2-shopping_admin-1) to attach to the
    # network so the instances can reach them. submit: without -WebAppUrls, instance i targets container i at its
    # address on the network, keeping the path of the template's webAppURL.
    [string[]]$EnvContainers,
    # submit: experimentId prefix, instance i gets "<prefix>-<i>". Defaults to the template's experimentId.
    [string]$ExperimentId,
    # submit: the agent query parameter of /api/evaluate.
    [string]$Agent = 'uncharted',
    # submit: where the generated task files, responses and logs go.
    [string]$RunDir,
    # submit: only write the per-instance task files, to check them before submitting.
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'

# Under pwsh -File a comma separated list arrives as one string.
if ($WebAppUrls) { $WebAppUrls = @($WebAppUrls -split ',' | ForEach-Object Trim | Where-Object { $_ }) }
if ($EnvContainers) { $EnvContainers = @($EnvContainers -split ',' | ForEach-Object Trim | Where-Object { $_ }) }
if ($EnvContainers -and $EnvContainers.Count -ne $Count) { throw "Give one -EnvContainers entry per instance ($Count), got $($EnvContainers.Count)." }

# The address of a container on the network, or null if it is not attached.
function Get-NetworkIp([string]$container) {
    $ip = docker inspect $container --format "{{with index .NetworkSettings.Networks `"$Network`"}}{{.IPAddress}}{{end}}" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Container $container not found." }
    if ($ip) { $ip } else { $null }
}

$SeleniumImage = 'selenium/standalone-firefox@sha256:a17bbdea03f99f61d3ecf8f7b425a8e6dd7fb22dc1926bea454780fc3719cec2'
$ProjectDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$ProjectDirU = $ProjectDir -replace '\\', '/'

# Ports and addresses of instance i. Chosen not to clash with the single-instance defaults of cascon-experiment.bat
# (8076, 7080, 4444, 7900), the local model server (8080) or LogUI (8000).
function Get-Instance([int]$i) {
    $prefix = Get-NetworkPrefix
    [pscustomobject]@{
        Index         = $i
        OdoBotName    = "odobot-$i"
        SeleniumName  = "selenium-firefox-$i"
        OdoBotIp      = "$prefix.2.$i"
        SeleniumIp    = "$prefix.3.$i"
        ApiPort       = 9000 + $i
        GuidancePort  = 7100 + $i
        SeleniumPort  = 4500 + $i
        VncPort       = 7910 + $i
    }
}

$script:networkPrefix = $null
function Get-NetworkPrefix {
    if ($script:networkPrefix) { return $script:networkPrefix }
    $subnet = docker network inspect $Network --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}' 2>$null
    if (-not $subnet) { throw "Network $Network does not exist. Run -Action up first." }
    if ($subnet -notmatch '^(\d+)\.(\d+)\.0\.0/16$') { throw "Expected a /16 subnet on $Network, found $subnet." }
    $script:networkPrefix = "$($Matches[1]).$($Matches[2])"
    return $script:networkPrefix
}

function Test-PortFree([int]$port) {
    -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Ready($inst) {
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        $seleniumReady = $false
        try { $seleniumReady = (Invoke-RestMethod "http://localhost:$($inst.SeleniumPort)/status" -TimeoutSec 3).value.ready } catch {}
        $odobotUp = $false
        try { $null = Invoke-WebRequest "http://localhost:$($inst.ApiPort)/" -TimeoutSec 3 -SkipHttpErrorCheck; $odobotUp = $true } catch {}
        if ($seleniumReady -and $odobotUp) { return }
        Start-Sleep -Seconds 3
    }
    throw "Instance $($inst.Index) did not become ready within 5 minutes. See: docker logs $($inst.OdoBotName)"
}

function Invoke-Up {
    docker network inspect $Network *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Creating network $Network (172.23.0.0/16)"
        docker network create --subnet 172.23.0.0/16 $Network | Out-Null
    }
    docker image inspect $Image *> $null
    if ($LASTEXITCODE -ne 0) { throw "Image $Image not found. Build it: docker build -t $Image -f docker-gradle/Dockerfile ." }

    $instances = $Start..($Start + $Count - 1) | ForEach-Object { Get-Instance $_ }

    # Fail before starting anything if a port is taken by something other than our own containers.
    $ours = docker ps -a --format '{{.Names}}' | Where-Object { $_ -match '^(odobot|selenium-firefox)-\d+$' }
    foreach ($inst in $instances) {
        if ($ours -contains $inst.OdoBotName) { continue } # Replaced below.
        foreach ($port in $inst.ApiPort, $inst.GuidancePort, $inst.SeleniumPort, $inst.VncPort) {
            if (-not (Test-PortFree $port)) { throw "Port $port (instance $($inst.Index)) is already in use." }
        }
    }

    foreach ($inst in $instances) {
        Write-Host "Starting instance $($inst.Index): $($inst.OdoBotName) @ $($inst.OdoBotIp), $($inst.SeleniumName) @ $($inst.SeleniumIp)"
        docker rm -f $inst.OdoBotName $inst.SeleniumName *> $null

        # Same flags as cascon-experiment.bat: OdoX's moz-extension pages need MOZ_REMOTE_ALLOW_SYSTEM_ACCESS, and the
        # grid must not close the session while OdoBot drives Firefox through OdoX instead of WebDriver.
        docker run -d --name $inst.SeleniumName --network $Network --ip $inst.SeleniumIp --shm-size=2g `
            -e MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 -e SE_NODE_SESSION_TIMEOUT=86400 `
            -p "$($inst.SeleniumPort):4444" -p "$($inst.VncPort):7900" $SeleniumImage | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not start $($inst.SeleniumName)." }

        docker run -d --name $inst.OdoBotName --network $Network --ip $inst.OdoBotIp `
            -p "$($inst.ApiPort):8076" -p "$($inst.GuidancePort):7080" `
            -v "$ProjectDirU/config:/application/config" `
            -v "$ProjectDirU/execution_events:/application/execution_events" `
            -v "$ProjectDirU/libs:/application/libs" `
            -v "$ProjectDirU/db:/application/db" `
            $Image | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not start $($inst.OdoBotName)." }
    }

    foreach ($container in $EnvContainers) {
        if (-not (Get-NetworkIp $container)) {
            Write-Host "Attaching $container to $Network"
            docker network connect $Network $container
            if ($LASTEXITCODE -ne 0) { throw "Could not attach $container to $Network." }
        }
        Write-Host "  $container is at $(Get-NetworkIp $container) on $Network"
    }

    Write-Host 'Waiting for the instances to become ready...'
    foreach ($inst in $instances) { Wait-Ready $inst }
    Invoke-Status
}

function Invoke-Status {
    $running = docker ps --format '{{.Names}}'
    $Start..($Start + $Count - 1) | ForEach-Object { Get-Instance $_ } | ForEach-Object {
        [pscustomobject]@{
            Instance     = $_.Index
            OdoBot       = if ($running -contains $_.OdoBotName) { 'up' } else { 'down' }
            Firefox      = if ($running -contains $_.SeleniumName) { 'up' } else { 'down' }
            Api          = "http://localhost:$($_.ApiPort)"
            Vnc          = "http://localhost:$($_.VncPort)"
            GuidanceHost = "$($_.OdoBotIp):7080"
            GridUrl      = "http://$($_.SeleniumIp):4444"
        }
    } | Format-Table -AutoSize
}

# Scheme and authority of a URL. Matched as text so placeholder hosts like http://<SHOPPING_IP>/admin work too.
function Get-Origin([string]$url) {
    if ($url -notmatch '^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]+)') { throw "Not an absolute URL: $url" }
    $Matches[1]
}

function New-TaskFile($inst, $template, [string]$path) {
    $def = $template | ConvertFrom-Json -Depth 50
    $def.experimentId = "$ExperimentId-$($inst.Index)"
    # Container addresses and ports: OdoX connects from inside the Firefox container, OdoBot reaches the grid on the network.
    $def.guidanceServiceHost = "$($inst.OdoBotIp):7080"
    $def.firefoxDockerGridURL = "http://$($inst.SeleniumIp):4444"

    if ($WebAppUrls) {
        $oldOrigin = Get-Origin $def.webAppURL
        $newUrl = $WebAppUrls[$inst.Index - $Start]
        $newOrigin = Get-Origin $newUrl
        $def.webAppURL = $newUrl
        foreach ($task in $def.tasks) {
            foreach ($prop in $task.PSObject.Properties) {
                $t = $prop.Value
                foreach ($field in 'userLocation', 'startUrl') {
                    if ($t.PSObject.Properties[$field] -and $t.$field.StartsWith($oldOrigin)) {
                        $t.$field = $newOrigin + $t.$field.Substring($oldOrigin.Length)
                    }
                }
            }
        }
        if ($def.PSObject.Properties['targetHosts']) {
            $def.targetHosts = @($def.targetHosts | ForEach-Object { if ($_ -eq $oldOrigin) { $newOrigin } else { $_ } })
        }
    }
    $def | ConvertTo-Json -Depth 50 | Set-Content -Path $path -Encoding utf8NoBOM
}

function Invoke-Submit {
    if (-not $Template) { throw '-Template is required for submit.' }
    $templatePath = (Resolve-Path $Template).Path
    $template = Get-Content $templatePath -Raw
    if (-not $ExperimentId) { $script:ExperimentId = ($template | ConvertFrom-Json).experimentId }
    if ($WebAppUrls -and $WebAppUrls.Count -ne $Count) { throw "Give one -WebAppUrls entry per instance ($Count), got $($WebAppUrls.Count)." }
    if (-not $WebAppUrls -and $EnvContainers) {
        $templateUrl = ($template | ConvertFrom-Json).webAppURL
        $path = $templateUrl.Substring((Get-Origin $templateUrl).Length)
        $script:WebAppUrls = @($EnvContainers | ForEach-Object {
            $ip = Get-NetworkIp $_
            if (-not $ip) { throw "$_ is not attached to $Network. Run -Action up with -EnvContainers first." }
            "http://$ip$path"
        })
    }
    if (-not $WebAppUrls) {
        Write-Warning 'No -WebAppUrls: every instance targets the same environment. Tasks that change application state will interfere with each other.'
    }

    $instances = $Start..($Start + $Count - 1) | ForEach-Object { Get-Instance $_ }

    if (-not $RunDir) { $RunDir = Join-Path $env:TEMP "odobot-instances/$ExperimentId-$(Get-Date -Format yyyyMMdd-HHmmss)" }
    New-Item -ItemType Directory -Force $RunDir | Out-Null

    if ($PrepareOnly) {
        foreach ($inst in $instances) { New-TaskFile $inst $template (Join-Path $RunDir "instance-$($inst.Index).json") }
        Write-Host "Task files written to $RunDir"
        return
    }

    $running = docker ps --format '{{.Names}}'
    foreach ($inst in $instances) {
        if ($running -notcontains $inst.OdoBotName -or $running -notcontains $inst.SeleniumName) {
            throw "Instance $($inst.Index) is not running. Run -Action up -Count $Count -Start $Start first."
        }
        $existing = Join-Path $ProjectDir "execution_events/$ExperimentId-$($inst.Index)"
        if (Test-Path $existing) {
            Write-Warning "$existing already exists: tasks with outputs there are skipped, and its summary includes earlier runs."
        }
    }

    $jobs = foreach ($inst in $instances) {
        $taskFile = Join-Path $RunDir "instance-$($inst.Index).json"
        New-TaskFile $inst $template $taskFile
        Write-Host "Submitting instance $($inst.Index) ($ExperimentId-$($inst.Index)) to http://localhost:$($inst.ApiPort), watch at http://localhost:$($inst.VncPort) (password: secret)"
        Start-Job -Name "odobot-$($inst.Index)" -ArgumentList $inst.ApiPort, $Agent, $taskFile, (Join-Path $RunDir "instance-$($inst.Index)-response.txt") -ScriptBlock {
            param($port, $agent, $taskFile, $responseFile)
            # The request returns when the whole experiment has finished.
            $code = curl.exe -s -S -X POST "http://localhost:$port/api/evaluate?agent=$agent" -H 'Content-Type: application/json' `
                --data-binary "@$taskFile" -o $responseFile -w '%{http_code}'
            [pscustomobject]@{ CurlExit = $LASTEXITCODE; HttpCode = $code }
        }
    }

    Write-Host "Waiting for $Count experiments to finish (run files in $RunDir)..."
    $results = foreach ($inst in $instances) {
        $job = $jobs | Where-Object Name -eq "odobot-$($inst.Index)"
        $r = $job | Wait-Job | Receive-Job
        Remove-Job $job
        docker logs $inst.OdoBotName > (Join-Path $RunDir "instance-$($inst.Index)-odobot.log") 2>&1
        [pscustomobject]@{
            Instance   = $inst.Index
            Experiment = "$ExperimentId-$($inst.Index)"
            Http       = if ($r.CurlExit -ne 0) { "curl exit $($r.CurlExit)" } else { $r.HttpCode }
            Summary    = "execution_events/$ExperimentId-$($inst.Index)/results/$ExperimentId-$($inst.Index)-tokens.json"
        }
    }
    $results | Format-Table -AutoSize
    Write-Host "Responses and OdoBot logs: $RunDir"
}

function Invoke-Down {
    foreach ($inst in ($Start..($Start + $Count - 1) | ForEach-Object { Get-Instance $_ })) {
        Write-Host "Removing instance $($inst.Index)"
        docker rm -f $inst.OdoBotName $inst.SeleniumName *> $null
    }
}

switch ($Action) {
    'up' { Invoke-Up }
    'status' { Invoke-Status }
    'submit' { Invoke-Submit }
    'down' { Invoke-Down }
}
