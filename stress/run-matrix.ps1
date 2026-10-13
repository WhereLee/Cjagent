# run-matrix.ps1 - the full stress matrix, reproducible end to end.
#
# For each allocation strategy (cabinet.allocation.strategy), for each round:
#   1. reset seeded fixtures (orders, door commands, compartments back to FREE) + sync redis
#   2. pre-log N stress users (dev mock login, one per thread) and grant each 10k points
#      directly in DB - the create endpoint rate-limits 20/min PER USER, so N users are the
#      honest way to say "N concurrent customers"; top-up API has a 100k cap + 10/min limit
#   3. round A x3 levels: ONE order per user (the one-open-order-per-customer rule rejects
#      a user's 2nd order, so threads == users == orders), ramped smoothly, rotated across the
#      1500 seeded cabinets (12000 small slots > 1000 orders) -> steady QPS/P99, ~100% success.
#      Steady load, not an instant burst: Windows RSTs when the accept backlog overflows,
#      so a burst measures the listener, not the allocator.
#   4. round B x1: 1000 users hammer ONE cabinet -> contention: success must equal the slot
#      count and DISTINCT(slot_id) must match (no oversell)
# The server boots once per strategy with a command-line override of cabinet.allocation.strategy;
# the hikari pool is sized for the run via the same override (deployment sizing, not business rule).
#
# Keep this file ASCII-only (PS 5.1 reads BOM-less files as GBK).
# Usage: powershell -File stress/run-matrix.ps1 [-Strategies pessimistic,cas,redisson,prealloc]

param(
    [string[]]$Strategies = @('pessimistic', 'cas', 'redisson', 'prealloc'),
    [int[]]$Levels = @(100, 500, 1000),
    [int]$ContentionThreads = 1000
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'server'
$results = Join-Path $PSScriptRoot 'results'
$props = Join-Path $results 'load.properties'
$usersFile = Join-Path $results 'stress-users.txt'
$jmeter = Join-Path $env:USERPROFILE 'Desktop\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
if (-not (Test-Path $jmeter)) { throw "jmeter.bat not found: $jmeter" }

$env2 = @{}
Get-Content (Join-Path $serverDir '.env') | Where-Object { $_ -match '^[A-Z]' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $env2[$kv[0]] = $kv[1]
}
$mysql = (Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $mysql) { throw 'mysql.exe not found' }
$db = if ($env2['MYSQL_DATABASE']) { $env2['MYSQL_DATABASE'] } else { 'cabinet_dev' }
function Invoke-Sql([string]$sql) {
    $env:MYSQL_PWD = $env2['MYSQL_PASSWORD']
    & $mysql -h $env2['MYSQL_HOST'] -P $env2['MYSQL_PORT'] -u $env2['MYSQL_USERNAME'] -D $db -N -B -e $sql
}
function Reset-Fixtures {
    # stress-scope only: seeded sites (91001+), their orders and door commands. Never touches dev demo data.
    # All three candidate conditions must be reset (SlotCandidateQuery): status=FREE AND door_open_at IS NULL
    # AND anomaly IS NULL - clearing only status leaves the slots unassignable and the whole round 10409s.
    Invoke-Sql "delete from biz_device_command where order_id in (select id from biz_storage_order where site_id >= 91001 and tenant_id = 1);" | Out-Null
    Invoke-Sql "delete from biz_storage_order where site_id >= 91001 and tenant_id = 1;" | Out-Null
    Invoke-Sql "update biz_compartment set status = 'FREE', door_open_at = NULL, anomaly = NULL, anomaly_at = NULL, anomaly_reason = NULL where id >= 93001;" | Out-Null
}

$jvmLine = Get-Content (Join-Path $root 'deploy\jvm.opts') | Where-Object { $_ -match '^JAVA_OPTS=' } | Select-Object -First 1
$jvmArgs = $jvmLine.Substring('JAVA_OPTS='.Length).Trim()

function Wait-Healthy([System.Diagnostics.Process]$proc) {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { throw "server process exited early (strategy boot failed)" }
        try {
            $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/actuator/health' -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return }
        } catch { Start-Sleep -Seconds 3; continue }
        Start-Sleep -Seconds 2
    }
    throw "server did not become healthy in 150s"
}

function Quantile($values, [double]$p) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $idx = [math]::Ceiling($p * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    return [int]$sorted[$idx]
}

function Run-Jmeter([string]$jtlName, [int]$threads, [int]$loops, [int]$ramp, [string]$mode, [int]$cabinetPool) {
    $jtl = Join-Path $results $jtlName
    if (Test-Path $jtl) { Remove-Item $jtl -Force }
    $ErrorActionPreference = 'Continue'
    $jmeterArgs = @('-n', '-t', (Join-Path $PSScriptRoot 'slot-allocation.jmx'), '-l', $jtl,
        '-q', $props, "-Jthreads=$threads", "-Jloops=$loops", "-Jramp=$ramp", "-JcabinetMode=$mode",
        "-JcabinetPool=$cabinetPool", "-JusersFile=$usersFile")
    & $jmeter @jmeterArgs 2>$null | Out-Null
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path $jtl)) { throw "no jtl produced: $jtlName" }
    return $jtl
}

function Get-Stats([string]$jtl) {
    $rows = Import-Csv $jtl
    $ok = @($rows | Where-Object { $_.label -eq 'create-order-0' })
    $noResp = @($rows | Where-Object { $_.label -like '*noresponse*' }).Count
    $elapsed = @($rows | ForEach-Object { [int]$_.elapsed })
    $okElapsed = @($ok | ForEach-Object { [int]$_.elapsed })
    $rejected = @($rows | Where-Object { $_.label -eq 'create-order-10409' }).Count
    $limited = @($rows | Where-Object { $_.label -eq 'create-order-42900' }).Count
    $sysErr = @($rows | Where-Object { $_.label -eq 'create-order-50000' }).Count
    $start = [long]($rows | ForEach-Object { [int64]$_.timeStamp } | Measure-Object -Minimum).Minimum
    $end = [long]($rows | ForEach-Object { [int64]$_.timeStamp + [int]$_.elapsed } | Measure-Object -Maximum).Maximum
    $secs = [math]::Max(($end - $start) / 1000.0, 0.001)
    return [pscustomobject]@{
        total = $rows.Count; ok = $ok.Count; rejected = $rejected; noResp = $noResp
        limited = $limited; sysErr = $sysErr
        qps = [math]::Round($rows.Count / $secs, 1)
        okQps = [math]::Round($ok.Count / $secs, 1)
        p50 = (Quantile $elapsed 0.50); p95 = (Quantile $elapsed 0.95); p99 = (Quantile $elapsed 0.99)
        okP99 = (Quantile $okElapsed 0.99)
    }
}

# pre-log one stress user per thread (mock login is not rate-limited); each gets 10k points
# straight in the DB - one round costs at most 800 points per user (single order), 12x headroom.
# The pool is built once per run and reused across rounds; phones are '138'+8-digit index
# (11 digits, unique per user).
$script:tokenPool = @()
function New-StressUsers([int]$count, [string]$tag) {
    while ($script:tokenPool.Count -lt $count) {
        $i = $script:tokenPool.Count + 1
        $body = '{"code":"stress-' + $tag + '-u' + $i + '","tenantCode":"platform","phone":"138' + $i.ToString('00000000') + '"}'
        $login = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/mini/auth/login' -Method Post `
            -ContentType 'application/json' -Body $body -TimeoutSec 15
        if (-not $login.data.accessToken) { throw "login failed for user $i" }
        $script:tokenPool += $login.data.accessToken
    }
    Invoke-Sql ("insert into biz_point_account (id, tenant_id, customer_id, points, frozen_points, version, create_time, update_time, deleted) " +
        "select c.id + 800000000, c.tenant_id, c.id, 10000, 0, 0, NOW(3), NOW(3), 0 from biz_customer c " +
        "where c.open_id like 'mock-openid-stress-%' on duplicate key update points = 10000, frozen_points = 0;") | Out-Null
    Set-Content -Path $usersFile -Value ($script:tokenPool | Select-Object -First $count) -Encoding ASCII
    Write-Output ("stress users ready: {0} tokens in file (pool {1})" -f $count, $script:tokenPool.Count)
}

$summary = @()
foreach ($strategy in $Strategies) {
    Write-Output "==== strategy: $strategy ===="

    $outLog = Join-Path $results ("server-$strategy.log")
    # PS 5.1 Start-Process does NOT quote ArgumentList elements: any element with spaces
    # (the JVM opts line) must carry its own literal quotes or mvn receives shredded args
    # and dies with NoPluginFoundForPrefixException. Runtime sizing (not business rules):
    # hikari 60 (default 10 turns concurrency into 50000s) + tomcat 400 threads/accept 2000
    # (default 200 turns 500+ instantaneous connects into connection refusals, which would
    # measure the load generator against the queue, not the allocator against the load).
    $proc = Start-Process -FilePath 'mvn.cmd' -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden `
        -ArgumentList @('-B', '-ntp', 'spring-boot:run',
            "`"-Dspring-boot.run.jvmArguments=$jvmArgs`"",
            '-Dspring-boot.run.profiles=dev',
            "`"-Dspring-boot.run.arguments=--cabinet.alloc.strategy=$strategy --spring.datasource.hikari.maximum-pool-size=60 --server.tomcat.threads.max=400 --server.tomcat.accept-count=2000`"") `
        -RedirectStandardOutput $outLog -RedirectStandardError (Join-Path $results "server-$strategy.err.log")
    try {
        Wait-Healthy $proc
        Write-Output "server up ($strategy)"

        foreach ($level in $Levels) {
            Reset-Fixtures
            & (Join-Path $PSScriptRoot 'sync-all-free-sets.ps1') | Out-Null
            New-StressUsers $level "$strategy-A$level"

            $jtl = Run-Jmeter "$strategy-A$level.jtl" $level 1 (10 + $level / 25) 'rotate' 1500
            $s = Get-Stats $jtl
            $summary += [pscustomobject]@{ round = "$strategy-A$level"; s = $s }
            Write-Output ("A{0,-5} ok={1}/{2} rej={3} noresp={4} lim={5} sys={6} qps={7} okQps={8} p50={9}ms p95={10}ms p99={11}ms okP99={12}ms" -f `
                $level, $s.ok, $s.total, $s.rejected, $s.noResp, $s.limited, $s.sysErr, $s.qps, $s.okQps, $s.p50, $s.p95, $s.p99, $s.okP99)
        }

        # contention round: 1000 distinct users hammer one cabinet (props.cabinet), oversell check after
        Reset-Fixtures
        & (Join-Path $PSScriptRoot 'sync-all-free-sets.ps1') | Out-Null
        New-StressUsers $ContentionThreads "$strategy-B"
        $jtl = Run-Jmeter "$strategy-B$ContentionThreads.jtl" $ContentionThreads 1 10 'fixed' 1500
        $s = Get-Stats $jtl
        $summary += [pscustomobject]@{ round = "$strategy-B$ContentionThreads"; s = $s }
        $sold = [int](Invoke-Sql "select count(*) from biz_storage_order where site_id >= 91001 and tenant_id = 1 and status in ('RESERVED','OPENING','ACTIVE');")
        $distinct = [int](Invoke-Sql "select count(distinct slot_id) from biz_storage_order where site_id >= 91001 and tenant_id = 1 and status in ('RESERVED','OPENING','ACTIVE') and slot_id is not null;")
        Write-Output ("B{0,-5} ok={1}/{2} rej={3} noresp={4} p50={5}ms p99={6}ms | sold={7} distinctSlots={8} oversell={9}" -f `
            $ContentionThreads, $s.ok, $s.total, $s.rejected, $s.noResp, $s.p50, $s.p99, $sold, $distinct, ($sold - $distinct))
    } finally {
        if (-not $proc.HasExited) { taskkill /PID $proc.Id /T /F | Out-Null }
        Start-Sleep -Seconds 3
    }
}

Write-Output ''
Write-Output '==== summary ===='
$summary | ForEach-Object { '{0,-20} ok={1,4}/{2,4} okQps={3,6} p50={4,5} p95={5,5} p99={6,6} okP99={7,6}' -f `
    $_.round, $_.s.ok, $_.s.total, $_.s.okQps, $_.s.p50, $_.s.p95, $_.s.p99, $_.s.okP99 }
