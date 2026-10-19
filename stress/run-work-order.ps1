# run-work-order.ps1 - one JMeter smoke round for the work-order plan.
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as GBK and
# Chinese comments break parsing.
#
# Usage:
#   powershell -File stress/run-work-order.ps1 -AdminToken "<jwt>" -QueueThreads 10 -ChatThreads 10 -Loops 5
# Requires stress/results/stress-users.txt (one mini token per line) for the AI chat group.

param(
    [Parameter(Mandatory = $true)][string]$AdminToken,
    [int]$QueueThreads = 10,
    [int]$ChatThreads = 10,
    [int]$Loops = 5,
    [int]$Ramp = 1,
    [string]$Tag = 'work-order'
)

$ErrorActionPreference = 'Stop'

$results = Join-Path $PSScriptRoot 'results'
$usersFile = Join-Path $results 'stress-users.txt'
$jtl = Join-Path $results ("$Tag-$QueueThreads-$ChatThreads.jtl")
$jmeterHome = Join-Path $env:USERPROFILE 'Desktop\tools\apache-jmeter-5.6.3'
$jmeter = Join-Path $jmeterHome 'bin\jmeter.bat'

if (-not (Test-Path $jmeter)) { throw "jmeter.bat not found: $jmeter" }
if (-not (Test-Path $usersFile)) { throw "missing $usersFile (one mini token per line)" }
New-Item -ItemType Directory -Force -Path $results | Out-Null
if (Test-Path $jtl) { Remove-Item $jtl -Force }

Write-Output "== work-order: queue=$QueueThreads chat=$ChatThreads loops=$Loops =="

$ErrorActionPreference = 'Continue'
$jmeterArgs = @(
    '-n',
    '-t', (Join-Path $PSScriptRoot 'work-order.jmx'),
    '-l', $jtl,
    "-JadminToken=$AdminToken",
    "-JqueueThreads=$QueueThreads",
    "-JchatThreads=$ChatThreads",
    "-Jloops=$Loops",
    "-Jramp=$Ramp",
    "-JusersFile=$usersFile"
)
& $jmeter @jmeterArgs 2>$null | Select-String -Pattern '^summary =' | ForEach-Object { $_.Line.Trim() }
$ErrorActionPreference = 'Stop'

if (-not (Test-Path $jtl)) { throw "no jtl produced" }

$rows = Import-Csv -Path $jtl
$groups = $rows | Group-Object -Property label

function Quantile($values, $p) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $idx = [math]::Ceiling($p * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    return [int]$sorted[$idx]
}

$header = '{0,-20} {1,7} {2,8} {3,8} {4,8} {5,8} {6,8}' -f 'label', 'count', 'avg_ms', 'p95_ms', 'p99_ms', 'max_ms', 'err%'
Write-Output $header
foreach ($g in ($groups | Sort-Object -Property Name)) {
    $elapsed = $g.Group | ForEach-Object { [int]$_.elapsed }
    $failed = @($g.Group | Where-Object { $_.success -ne 'true' }).Count
    $errPct = if ($g.Count -gt 0) { [math]::Round(100.0 * $failed / $g.Count, 2) } else { 0 }
    '{0,-20} {1,7} {2,8} {3,8} {4,8} {5,8} {6,8}' -f `
        $g.Name, $g.Count, `
        [math]::Round((($elapsed | Measure-Object -Average).Average), 1), `
        (Quantile $elapsed 0.95), (Quantile $elapsed 0.99), `
        (($elapsed | Measure-Object -Maximum).Maximum), $errPct
}
Write-Output "jtl: $jtl"
