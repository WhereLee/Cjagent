# run-round.ps1 - one JMeter round for the slot-allocation plan, then per-label stats.
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as GBK and
# Chinese comments break parsing.
#
# Usage:  powershell -File stress/run-round.ps1 -Threads 500 -Tag pessimistic
# Requires stress/results/load.properties with token=..., cabinet=..., size=...

param(
    [Parameter(Mandatory = $true)][int]$Threads,
    [Parameter(Mandatory = $true)][string]$Tag,
    [int]$Loops = 1,
    [int]$Ramp = 1
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$results = Join-Path $PSScriptRoot 'results'
$props = Join-Path $results 'load.properties'
$jtl = Join-Path $results ("$Tag-$Threads.jtl")
$jmeterHome = Join-Path $env:USERPROFILE 'Desktop\tools\apache-jmeter-5.6.3'
$jmeter = Join-Path $jmeterHome 'bin\jmeter.bat'

if (-not (Test-Path $jmeter)) { throw "jmeter.bat not found: $jmeter" }
if (-not (Test-Path $props)) { throw "missing $props (token/cabinet/size)" }
New-Item -ItemType Directory -Force -Path $results | Out-Null
if (Test-Path $jtl) { Remove-Item $jtl -Force }

Write-Output "== round: tag=$Tag threads=$Threads loops=$Loops =="

# jmeter.bat writes WARN lines to stderr; with ErrorActionPreference=Stop those become
# terminating NativeCommandError records and kill the run halfway. Relax it for the call only.
#
# NOTE: `-Jthreads=$Threads` inline is silently broken by Windows PowerShell 5.1 (the value
# is dropped, so JMeter starts with 0 threads and reports "0 in 00:00:00"). Arguments that
# contain `=` plus a variable MUST be built as a quoted array element and splatted.
# $args 是 PowerShell 自动变量，不得复用，故命名 jmeterArgs
$ErrorActionPreference = 'Continue'
$jmeterArgs = @(
    '-n',
    '-t', (Join-Path $PSScriptRoot 'slot-allocation.jmx'),
    '-l', $jtl,
    '-q', $props,
    "-Jthreads=$Threads",
    "-Jloops=$Loops",
    "-Jramp=$Ramp"
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

$header = '{0,-26} {1,7} {2,8} {3,8} {4,8} {5,8} {6,8}' -f 'label', 'count', 'avg_ms', 'p95_ms', 'p99_ms', 'max_ms', 'err%'
Write-Output $header
foreach ($g in ($groups | Sort-Object -Property Name)) {
    $elapsed = $g.Group | ForEach-Object { [int]$_.elapsed }
    $failed = @($g.Group | Where-Object { $_.success -ne 'true' }).Count
    $errPct = if ($g.Count -gt 0) { [math]::Round(100.0 * $failed / $g.Count, 2) } else { 0 }
    '{0,-26} {1,7} {2,8} {3,8} {4,8} {5,8} {6,8}' -f `
        $g.Name, $g.Count, `
        [math]::Round((($elapsed | Measure-Object -Average).Average), 1), `
        (Quantile $elapsed 0.95), (Quantile $elapsed 0.99), `
        (($elapsed | Measure-Object -Maximum).Maximum), $errPct
}

# Throughput of successful creates only: wall clock from first sample to last.
$ok = @($rows | Where-Object { $_.label -like 'create-order-0' })
if ($ok.Count -gt 1) {
    $start = [long]($ok | ForEach-Object { [int64]$_.timestamp } | Measure-Object -Minimum).Minimum
    $end = [long]($ok | ForEach-Object { [int64]($_.timestamp.Trim()) + [int64]$_.elapsed } | Measure-Object -Maximum).Maximum
    $secs = [math]::Max(($end - $start) / 1000.0, 0.001)
    Write-Output ('successful creates: {0} in {1}s = {2}/s' -f $ok.Count, [math]::Round($secs, 2), [math]::Round($ok.Count / $secs, 1))
}
