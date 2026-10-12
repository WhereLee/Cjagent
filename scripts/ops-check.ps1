# Light-weight ops check for the cabinet server. ASCII-only output on purpose:
# PS 5.1 + GBK console garbles Chinese, and a runbook that prints garbage is worse
# than no runbook. Exit codes: 0 = pass, 1 = warn, 2 = fail.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\ops-check.ps1 -Dir server
#   (on the server: -Dir points at the deploy directory that contains logs\)
#
# Checks:
#   1. /actuator/health answers 200 with "UP"
#   2. free space on the drive holding the deploy dir
#   3. no JVM crash/OOM artifacts (hs_err_pid*, *.hprof) in logs\
#   4. today's error-log line count
#   5. last Full GC pause from logs\gc.log (warn >= 200 ms, fail >= 1000 ms)
#   6. jstat -gcutil of the cabinet JVM when jps/jstat are on PATH (best effort)
#
# The script NEVER deletes anything. Dumps listed in check 3 must be inspected
# (see the ops manual, "Java ops basics") and removed by a human.

param(
    [string]$Dir = ".",
    [int]$Port = 8080,
    # Heuristic levels for check 5, in milliseconds. Tune per server, not per mood.
    [double]$GcWarnMs = 200,
    [double]$GcFailMs = 1000
)

$script:Worst = 0
function Rank([int]$level) { if ($level -gt $script:Worst) { $script:Worst = $level } }
function Out([string]$level, [string]$msg) { Write-Host ("[{0}] {1}" -f $level, $msg) }

$logsDir = Join-Path $Dir "logs"
if (-not (Test-Path $logsDir)) {
    Out "FAIL" ("logs dir not found: {0} (pass -Dir with the deploy directory)" -f $logsDir)
    exit 2
}

# --- 1. health endpoint -------------------------------------------------------
try {
    $resp = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/actuator/health" -f $Port) `
        -UseBasicParsing -TimeoutSec 5
    if ($resp.StatusCode -eq 200 -and $resp.Content -match '"UP"') {
        Out "PASS" "health endpoint is UP"
    } else {
        Out "FAIL" ("health endpoint answered HTTP {0}: {1}" -f $resp.StatusCode, $resp.Content)
        Rank 2
    }
} catch {
    Out "FAIL" ("health endpoint unreachable (is the app running on port {0}?): {1}" -f $Port, $_.Exception.Message)
    Rank 2
}

# --- 2. disk free -------------------------------------------------------------
try {
    $fullDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Dir))
    $driveName = $fullDir.Substring(0, 1)
    $drive = Get-PSDrive -Name $driveName -ErrorAction Stop
    $freeGB = [math]::Round($drive.Free / 1GB, 2)
    if ($freeGB -ge 1) {
        Out "PASS" ("disk {0}: free {1} GB" -f $driveName, $freeGB)
    } elseif ($freeGB -ge 0.2) {
        Out "WARN" ("disk {0}: only {1} GB free - a heap dump will not fit" -f $driveName, $freeGB)
        Rank 1
    } else {
        Out "FAIL" ("disk {0}: {1} GB free - clean up before the next OOM fills it" -f $driveName, $freeGB)
        Rank 2
    }
} catch {
    Out "WARN" ("disk check skipped: {0}" -f $_.Exception.Message)
    Rank 1
}

# --- 3. crash / OOM artifacts -------------------------------------------------
$dumpPatterns = @("*.hprof", "hs_err_pid*")
$found = @()
foreach ($p in $dumpPatterns) {
    $found += @(Get-ChildItem -Path $logsDir -Filter $p -File -ErrorAction SilentlyContinue)
}
if ($found.Count -gt 0) {
    foreach ($f in $found) {
        Out "FAIL" ("JVM crash/OOM artifact: {0} ({1} KB, written {2}) - analyze then remove by hand" -f $f.Name, [math]::Round($f.Length / 1KB), $f.LastWriteTime)
    }
    Rank 2
} else {
    Out "PASS" "no heap dumps or hs_err files"
}

# --- 4. today's error lines ---------------------------------------------------
$today = Get-Date -Format "yyyy-MM-dd"
$todayErrorLog = Join-Path $logsDir ("cabinet-server-error.{0}.log" -f $today)
if (Test-Path $todayErrorLog) {
    $lines = @(Get-Content $todayErrorLog).Count
    if ($lines -gt 0) {
        Out "WARN" ("error log for {0}: {1} lines - tail it before anything else" -f $today, $lines)
        Rank 1
    } else {
        Out "PASS" "error log for today is empty"
    }
} else {
    Out "PASS" "no error log rolled for today yet"
}

# --- 5. GC pause from logs\gc.log --------------------------------------------
$gcLog = Join-Path $logsDir "gc.log"
if (Test-Path $gcLog) {
    $fullPauses = @(Select-String -Path $gcLog -Pattern "Pause Full")
    if ($fullPauses.Count -gt 0) {
        $maxMs = 0.0
        foreach ($hit in $fullPauses) {
            if ($hit.Line -match '([0-9.]+)ms') {
                $v = [double]$Matches[1]
                if ($v -gt $maxMs) { $maxMs = $v }
            }
        }
        Out "INFO" ("last full GC: {0}" -f $fullPauses[-1].Line.Trim())
        if ($maxMs -ge $GcFailMs) {
            Out "FAIL" ("worst full GC pause {0} ms (>= {1}): memory pressure, read the ops manual GC section" -f $maxMs, $GcFailMs)
            Rank 2
        } elseif ($maxMs -ge $GcWarnMs) {
            Out "WARN" ("worst full GC pause {0} ms (>= {1}): watch the trend" -f $maxMs, $GcWarnMs)
            Rank 1
        } else {
            Out "PASS" ("full GC pauses fine (worst {0} ms)" -f $maxMs)
        }
    } else {
        Out "PASS" "no full GC recorded (good for a 512m heap)"
    }
} else {
    Out "WARN" ("gc.log not found at {0} - JVM flags from deploy/jvm.opts not applied?" -f $gcLog)
    Rank 1
}

# --- 6. jstat counters (best effort) ------------------------------------------
$jps = Get-Command jps -ErrorAction SilentlyContinue
if ($null -eq $jps) {
    Out "INFO" "jps not on PATH; skipping GC counters (check 5 already reads gc.log)"
} else {
    $hit = (& jps -l 2>$null | Select-String "cabinet") | Select-Object -First 1
    if ($null -eq $hit) {
        Out "WARN" "jps works but no cabinet JVM found; if health is UP the process may run under another user"
        Rank 1
    } else {
        $javaPid = ($hit.ToString() -split '\s+')[0]
        $gcutil = & jstat -gcutil $javaPid 2>$null
        if ($LASTEXITCODE -eq 0 -and $gcutil) {
            Out "INFO" "jstat -gcutil (S0 S1 E O M YGC YGCT FGC FGCT GCT):"
            $gcutil | Select-Object -Last 2 | ForEach-Object { Write-Host ("       {0}" -f $_) }
        } else {
            Out "WARN" ("jstat failed for pid {0}" -f $javaPid)
            Rank 1
        }
    }
}

# --- verdict -------------------------------------------------------------------
if ($script:Worst -ge 2) {
    Out "FAIL" "ops-check verdict: FAIL - act now"
    exit 2
} elseif ($script:Worst -ge 1) {
    Out "WARN" "ops-check verdict: WARN - look into the WARN lines"
    exit 1
}
Out "PASS" "ops-check verdict: PASS"
exit 0
