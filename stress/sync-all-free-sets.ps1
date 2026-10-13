# sync-all-free-sets.ps1 - rebuild Redis free-slot sets for ALL seeded cabinets (92001+)
# in ONE redis connection: one SQL query, then the whole DEL+SADD command stream piped
# through redis-cli's stdin (27000 slots / ~4500 commands in ~10s; one call per cabinet
# would pay a fresh process start 1500 times).
#
# Stress-only helper (cut: stress numbers). Same key layout the reconciler owns:
#   cab:alloc:free:<cabinetId>:<sizeType>  in db 8
#
# Keep this file ASCII-only (PS 5.1 reads BOM-less files as GBK).
# Usage: powershell -File stress/sync-all-free-sets.ps1 [-MinCabinetId 92001]

param(
    [long]$MinCabinetId = 92001,
    [string]$RedisCli = 'F:\Redis\redis-cli.exe',
    [int]$RedisDb = 8
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env2 = @{}
Get-Content (Join-Path $root 'server\.env') | Where-Object { $_ -match '^[A-Z]' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $env2[$kv[0]] = $kv[1]
}
if (-not $env2['MYSQL_USERNAME']) { throw 'MYSQL_USERNAME missing in env file' }
if (-not (Test-Path $RedisCli)) { throw "redis-cli not found: $RedisCli" }

$mysql = (Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $mysql) { $mysql = (Get-Command mysql.exe -ErrorAction SilentlyContinue).Source }
if (-not $mysql) { throw 'mysql.exe not found' }

$db = if ($env2['MYSQL_DATABASE']) { $env2['MYSQL_DATABASE'] } else { 'cabinet_dev' }
$env:MYSQL_PWD = $env2['MYSQL_PASSWORD']

$sql = @"
select k.id, c.size_type, c.id
  from biz_cabinet k join biz_compartment c on c.cabinet_id = k.id
 where k.id >= $MinCabinetId and k.deleted = 0 and c.status = 'FREE' and c.deleted = 0
 order by k.id, c.id
"@
$rows = & $mysql -h $env2['MYSQL_HOST'] -P $env2['MYSQL_PORT'] -u $env2['MYSQL_USERNAME'] -D $db -N -B -e $sql
if ($LASTEXITCODE -ne 0) { throw "mysql query failed with exit $LASTEXITCODE" }

# every (cabinet,size) key gets a DEL, then one SADD per member - as one piped command stream
$byKey = @{}
foreach ($line in @($rows | Where-Object { $_ -and $_.Trim() -ne '' })) {
    $p = $line -split "`t"
    $key = "cab:alloc:free:$($p[0]):$($p[1])"
    if (-not $byKey.ContainsKey($key)) { $byKey[$key] = @() }
    $byKey[$key] += $p[2].Trim()
}

$commands = New-Object System.Collections.Generic.List[string]
$cabinets = @{}
foreach ($key in $byKey.Keys) {
    $parts = $key -split ':'
    $cabinets[$parts[3]] = $true
}
foreach ($cabId in $cabinets.Keys) {
    foreach ($size in @('LARGE', 'MEDIUM', 'SMALL')) {
        $key = "cab:alloc:free:${cabId}:${size}"
        $commands.Add("SELECT $RedisDb")
        $commands.Add("DEL $key")
        if ($byKey.ContainsKey($key)) {
            $commands.Add("SADD $key " + ($byKey[$key] -join ' '))
        }
    }
}

# SELECT once up front instead of per key (4000+ redundant SELECTs would still work but why)
$stream = $commands | Where-Object { $_ -ne "SELECT $RedisDb" }
$stream | & $RedisCli -n $RedisDb | Out-Null

$total = 0
foreach ($v in $byKey.Values) { $total += $v.Count }
Write-Output ("synced {0} cabinets, {1} free slots into {2} redis keys (piped)" -f $cabinets.Count, $total, $byKey.Count)
