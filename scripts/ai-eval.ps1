# ai-eval.ps1 - run the offline golden-question evaluation against the real model.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads a BOM-less file as GBK and
# Chinese comments break parsing.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/ai-eval.ps1            # all cases
#   powershell -ExecutionPolicy Bypass -File scripts/ai-eval.ps1 -Limit 3   # cost control
#
# The key is read from server/.env (MIMO_API_KEY) and never printed.
# CI never runs this: the test is tagged ai-eval and requires -Dai.eval=true.

param(
    [int]$Limit = 0,
    [string]$Only = ''
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root 'server\.env'
if (-not (Test-Path $envFile)) { throw "env file not found: $envFile" }

$key = $null
Get-Content $envFile | Where-Object { $_ -match '^MIMO_API_KEY=' } | ForEach-Object {
    $key = ($_ -split '=', 2)[1].Trim()
}
if (-not $key) {
    $key = $env:MIMO_API_KEY
}
if (-not $key) { throw 'MIMO_API_KEY is missing (server/.env or environment)' }
$env:MIMO_API_KEY = $key

$serverDir = Join-Path $root 'server'
Push-Location $serverDir
try {
    $mvnArgs = @('-B', '-Pintegration', 'test', '-Dtest=AiEvalTest', '-Dai.eval=true')
    if ($Limit -gt 0) { $mvnArgs += "-Dai.eval.limit=$Limit" }
    if ($Only) { $mvnArgs += "-Dai.eval.only=$Only" }
    Write-Output "running: mvn $($mvnArgs -join ' ')"
    & mvn @mvnArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
