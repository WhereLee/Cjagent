# dev-env.ps1 - start / stop / status of local middleware for the cabinet project.
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads a BOM-less file as
# GBK, so Chinese comments here would break parsing (ParserError). Chinese docs live
# in README.md / docs.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1            # start
#   powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1 status
#   powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1 stop
#
# Why this script exists: after a reboot Redis and RocketMQ do NOT come back by
# themselves (the Windows service "redis" needs admin to start, and RocketMQ is
# launched by a .bat). MySQL / PostgreSQL are services and do auto-start.
#
# Verification principle: never trust a command's own success output. Ports and
# PING are the only accepted evidence.

param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start'
)

$ErrorActionPreference = 'Stop'

$RedisHome   = 'F:\Redis'
$RedisExe    = Join-Path $RedisHome 'redis-server.exe'
$RedisCli    = Join-Path $RedisHome 'redis-cli.exe'
$RedisConf   = Join-Path $RedisHome 'redis.windows.conf'
$RmqHome     = 'D:\rocketmq-5.3.1'
$RmqStarter  = Join-Path $RmqHome 'start-all-detached.bat'
$RmqShutdown = 'D:\rocketmq-5.3.1\rocketmq-all-5.3.1-bin-release\bin\mqshutdown.cmd'

# port -> friendly name, used for both reporting and readiness checks
$Ports = [ordered]@{
    3306 = 'mysql'
    5432 = 'postgres'
    6379 = 'redis'
    9876 = 'rocketmq-namesrv'
    10911 = 'rocketmq-broker'
    8081 = 'rocketmq-proxy'
}

function Get-ListeningPorts {
    (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty LocalPort -Unique)
}

function Test-Port([int]$Port) {
    $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Show-Status {
    # IMPORTANT: the table goes to Write-Host (display only). If it were written to the
    # output stream it would be captured into the caller's variable together with the
    # count, and `$down -gt 0` would then compare an array instead of a number.
    $listening = Get-ListeningPorts
    Write-Host 'component          port   state'
    Write-Host '-----------------  -----  ------'
    $down = 0
    # iterate with GetEnumerator: on an OrderedDictionary, $Ports[3306] is treated as a
    # *positional* index (there is no 3306th element) instead of a key lookup, so it
    # silently returns nothing. Key-based access must go through the enumerator.
    foreach ($entry in $Ports.GetEnumerator()) {
        $port = $entry.Key
        if ($listening -contains $port) {
            $state = 'UP'
        } else {
            $state = 'DOWN'
            $down++
        }
        Write-Host ('{0,-17} {1,5}  {2}' -f @($entry.Value, $port, $state))
    }
    return $down
}

function Start-Env {
    Write-Output '== starting redis (process mode, service needs admin) =='
    if (-not (Test-Path $RedisExe)) { Write-Output "MISSING: $RedisExe"; exit 2 }
    if (Test-Port 6379) {
        Write-Output 'redis already up on 6379'
    } else {
        Start-Process -FilePath $RedisExe -ArgumentList $RedisConf -WorkingDirectory $RedisHome -WindowStyle Hidden
        if (-not (Wait-Port 6379 20)) { Write-Output 'FAILED: redis 6379 not listening'; exit 3 }
    }
    $ping = & $RedisCli -h 127.0.0.1 -p 6379 ping 2>&1
    Write-Output ("redis PING -> " + ($ping -join ' '))
    if (($ping -join '') -ne 'PONG') { Write-Output 'FAILED: redis PING not PONG'; exit 3 }

    Write-Output '== starting rocketmq (namesrv + broker + proxy) =='
    if (Test-Port 10911) {
        Write-Output 'rocketmq broker already up on 10911, skip'
    } else {
        if (-not (Test-Path $RmqStarter)) { Write-Output "MISSING: $RmqStarter"; exit 2 }
        # start-all-detached.bat spawns minimized windows and returns; start-rocketmq.bat ends with
        # a pause which would block automation, so it is deliberately not used here.
        Start-Process -FilePath $RmqStarter -WindowStyle Hidden
        if (-not (Wait-Port 9876 60))  { Write-Output 'FAILED: namesrv 9876 not listening'; exit 3 }
        if (-not (Wait-Port 10911 90)) { Write-Output 'FAILED: broker 10911 not listening'; exit 3 }
    }

    Write-Output '== final status (ports are the only accepted evidence) =='
    $down = Show-Status
    if ($down -gt 0) {
        Write-Output 'RESULT: DEGRADED'
        exit 4
    }
    Write-Output 'RESULT: READY'
}

function Stop-Env {
    Write-Output '== stopping rocketmq via mqshutdown (do not kill java blindly) =='
    if (Test-Path $RmqShutdown) {
        # mqshutdown.cmd takes: all | namesrv | broker
        & $RmqShutdown all 2>&1 | Select-Object -Last 3
    } else {
        Write-Output "NOT FOUND: $RmqShutdown - leaving rocketmq running"
    }

    Write-Output '== stopping redis (only the process we started, never the Windows service) =='
    Get-Process redis-server -ErrorAction SilentlyContinue | ForEach-Object {
        Stop-Process -Id $_.Id -Force
        Write-Output ('stopped redis-server pid=' + $_.Id)
    }

    Write-Output '== final status =='
    $down = Show-Status
    Write-Output ("components down: " + $down)
}

switch ($Action) {
    'start'  { Start-Env }
    'stop'   { Stop-Env }
    'status' { $d = Show-Status; if ($d -gt 0) { exit 4 } else { Write-Output 'RESULT: READY' } }
}
