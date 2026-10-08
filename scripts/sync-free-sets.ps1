# sync-free-sets.ps1 - rebuild the Redis free-slot sets of one cabinet from the DB truth.
#
# Why a script and not a button: the pre-deduction strategy (cabinet.alloc.strategy=prealloc)
# reads admission from Redis sets whose members MUST come from biz_compartment. In production
# this is the job of the reconciler (cut 13) and an ops endpoint with its own permission code
# (cut 14). For local stress runs we need the same effect without adding a half-baked API,
# so this dev-only script does exactly what the reconciler will do later.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as GBK.
#
# Usage: powershell -File scripts/sync-free-sets.ps1 -CabinetNo CAB-001-1

param(
    [Parameter(Mandatory = $true)][string]$CabinetNo,
    [string]$EnvFile = '',
    [string]$RedisCli = 'F:\Redis\redis-cli.exe',
    [int]$RedisDb = 8
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $root 'server\.env' }
if (-not (Test-Path $EnvFile)) { throw "env file not found: $EnvFile" }
if (-not (Test-Path $RedisCli)) { throw "redis-cli not found: $RedisCli" }

$env2 = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z]' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $env2[$kv[0]] = $kv[1]
}
if (-not $env2['MYSQL_USERNAME']) { throw 'MYSQL_USERNAME missing in env file' }

# mysql client path: newest 8.0 install under Program Files, else rely on PATH
$mysql = (Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $mysql) { $mysql = (Get-Command mysql.exe -ErrorAction SilentlyContinue).Source }
if (-not $mysql) { throw 'mysql.exe not found' }

$db = if ($env2['MYSQL_DATABASE']) { $env2['MYSQL_DATABASE'] } else { 'cabinet_dev' }
$env:MYSQL_PWD = $env2['MYSQL_PASSWORD']

# One pass per size; members are the free compartment ids of that cabinet.
# Note: the tenant condition is explicit here - raw SQL does not go through the tenant interceptor.
$sql = @"
select c.id, c.size_type
  from biz_cabinet k join biz_compartment c on c.cabinet_id = k.id
 where k.cabinet_no = '$CabinetNo' and c.status = 'FREE' and c.deleted = 0
 order by c.size_type, c.id
"@

$rows = & $mysql -h $env2['MYSQL_HOST'] -P $env2['MYSQL_PORT'] -u $env2['MYSQL_USERNAME'] -D $db -N -B -e $sql
if ($LASTEXITCODE -ne 0) { throw "mysql query failed with exit $LASTEXITCODE" }

$bySize = @{}
foreach ($line in @($rows | Where-Object { $_ -and $_.Trim() -ne '' })) {
    $parts = $line -split "`t"
    $id = $parts[0].Trim()
    $size = $parts[1].Trim()
    if (-not $bySize.ContainsKey($size)) { $bySize[$size] = @() }
    $bySize[$size] += $id
}

$cabinetId = (& $mysql -h $env2['MYSQL_HOST'] -P $env2['MYSQL_PORT'] -u $env2['MYSQL_USERNAME'] -D $db -N -B -e `
    "select id from biz_cabinet where cabinet_no = '$CabinetNo'")
$cabinetId = "$cabinetId".Trim()
if (-not $cabinetId) { throw "cabinet not found: $CabinetNo" }

foreach ($size in $bySize.Keys) {
    $key = "cab:alloc:free:${cabinetId}:${size}"
    # DEL first: a stale member would sell a compartment that is no longer free.
    & $RedisCli -n $RedisDb DEL $key | Out-Null
    if ($bySize[$size].Count -gt 0) {
        $cmdArgs = @('-n', $RedisDb, 'SADD', $key) + $bySize[$size]
        $added = & $RedisCli @cmdArgs
        Write-Output ("{0,-8} {1,4} free slots -> {2} (SADD={3})" -f $size, $bySize[$size].Count, $key, $added)
    } else {
        Write-Output ("{0,-8} {1,4} free slots -> {2} (emptied)" -f $size, 0, $key)
    }
}

Write-Output ("done. cabinetId={0} sizes={1}" -f $cabinetId, ($bySize.Keys -join ','))
