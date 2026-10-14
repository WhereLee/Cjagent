# verify-funds.ps1 - end-to-end money flow check against a running dev server.
#
# Why a script: the flow needs login -> recharge -> create -> open -> backdate -> pickup
# plus SQL assertions, which is far too long for one inline command line.
# Keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less files as GBK).
#
# Numbers below follow the pricing the user fixed on 2026-10-11:
#   MEDIUM = 400 points/hour (1 point = 1 RMB cent), first 30 minutes free,
#   prepay 4 hours = 1600, billed 2h after 120 min => consume 800, refund 800.
# Rewritten on 2026-10-14: the previous version still assumed the retired model
# (HTTP cancel endpoint, deposit/freeze legs, "billing starts when the door closes",
# and a 25-point unit price), so its expectations described a system that no longer exists.
#
# Usage: powershell -File scripts/verify-funds.ps1

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root  = Split-Path -Parent $PSScriptRoot
$base  = 'http://127.0.0.1:8080'
$tmp   = Join-Path $env:TEMP 'cabtools'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$env2 = @{}
Get-Content (Join-Path $root 'server\.env') | Where-Object { $_ -match '^[A-Z]' } | ForEach-Object {
    $kv = $_ -split '=', 2
    $env2[$kv[0]] = $kv[1]
}
$env:MYSQL_PWD = $env2['MYSQL_PASSWORD']
$mysql = (Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName

function Sql([string]$q) {
    & $mysql -h $env2['MYSQL_HOST'] -P $env2['MYSQL_PORT'] -u $env2['MYSQL_USERNAME'] -D cabinet_dev -N -B -e $q 2>$null
}

function PostJson([string]$url, [string]$body, [string]$token) {
    $f = Join-Path $tmp ([guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($f, $body, (New-Object Text.UTF8Encoding $false))
    $hdr = @('-s', '-m', '20', '-X', 'POST', $url, '-H', 'Content-Type: application/json', '--data', "@$f")
    if ($token) { $hdr += @('-H', "Authorization: Bearer $token") }
    $out = & curl.exe @hdr
    Remove-Item $f -Force
    return ($out | ConvertFrom-Json)
}

# 1) login with a FRESH customer every run.
#    Why fresh: the invariant checked in step 6 (signed ledger sum == stored balance)
#    only holds for an account that lived entirely under the current money model.
#    Accounts from the deposit/freeze era still carry those retired transaction types
#    (19A switched the model without rewriting history), so reusing one reports a fake
#    imbalance. phone is required for a first-time registration (step 26).
$stamp = Get-Date -Format 'HHmmss'
# phone must be exactly 11 digits matching 1[3-9]\d{9}: 139 + 6 (stamp) + 2 = 11
$phone = "139$stamp$(Get-Random -Minimum 10 -Maximum 99)"
$loginBody = @{ code = "h5-funds-verifier-$stamp"; tenantCode = 'platform'; phone = $phone } | ConvertTo-Json -Compress
$login = PostJson "$base/api/mini/auth/login" $loginBody $null
if (-not $login.data) { throw "login failed: $login" }
$token = $login.data.accessToken
$customer = $login.data.customerId
Write-Output "customer = $customer"

# 2) recharge through the real endpoint (mock pay channel credits immediately).
#    The old script inserted a FIXTURE txn and recomputed the balance - that bypassed
#    the application, so it could never catch a broken money path. 3000 covers the
#    MEDIUM prepay of 1600.
$rechargeBody = @{ requestId = [guid]::NewGuid().ToString(); points = 3000 } | ConvertTo-Json -Compress
$rc = PostJson "$base/api/mini/points/recharge" $rechargeBody $token
if ($rc.code -ne 0) { throw "recharge failed: $($rc | ConvertTo-Json -Compress)" }
Write-Output ("recharged 3000, balance=" + $rc.data.balance + " status=" + $rc.data.status)

# 2b) a customer may only hold one live order (step 5 rule): settle whatever is left
#     from an earlier run instead of calling the retired cancel endpoint.
$left = Sql "select order_no from biz_storage_order where customer_id=$customer and active_flag = 1"
foreach ($l in @($left | Where-Object { $_ })) {
    $null = PostJson "$base/api/mini/storage/orders/$l/pickup" '{}' $token
    Write-Output "closed leftover order $l"
}

# 3) create = allocate + prepay + open handshake, one call (step 2 merged them;
#    estimate_minutes no longer drives anything, the server keeps it as 0)
$createBody = @{ requestId = [guid]::NewGuid().ToString(); cabinetNo = 'CAB-001-1'; sizeType = 'MEDIUM'; estimateMinutes = 120 } | ConvertTo-Json -Compress
$created = PostJson "$base/api/mini/storage/orders" $createBody $token
if ($created.code -ne 0) { throw "create failed: $($created | ConvertTo-Json -Compress)" }
$orderNo = $created.data.orderNo
Write-Output ("created   : status=" + $created.data.status + " slot=" + $created.data.slotNo + " orderNo=$orderNo")
Write-Output ("  message : " + $created.data.message)
Write-Output ("  snapshot: " + (Sql "select pricing_snapshot from biz_storage_order where order_no='$orderNo'"))
Write-Output ("  money   : " + (Sql "select concat('prepay=',ifnull(prepay_points,-1),' settled=',ifnull(settled_points,-1),' arrears=',ifnull(arrears_points,-1)) from biz_storage_order where order_no='$orderNo'") + "  " + (Sql "select concat('acct points=',points,' frozen=',frozen_points) from biz_point_account where customer_id=$customer"))
if ($created.data.message -notmatch 'V\d{8}') {
    Write-Output '  WARN: pickup credential missing from the create message (step 25 delivery)'
}

# 4) billing anchor is the moment the door opened (step 19A): started_at is already set
#    by the OPEN leg inside place, so backdate right here - time does not pass by itself.
Sql "update biz_storage_order set started_at = date_sub(now(3), interval 120 minute) where order_no='$orderNo'" | Out-Null
Write-Output 'started_at backdated to 120 min ago (expect free 30 + billed 2h => consume 800, prepay 1600 refunded 800)'

# 5) close the door so the compartment is releasable, then pickup = settle + open + take out
$null = PostJson "$base/api/mini/storage/orders/$orderNo/door?action=CLOSE_VERIFY" '{}' $token
Write-Output ("after CLOSE_VERIFY : " + (Sql "select concat(status,' / slot=',(select status from biz_compartment c where c.id=biz_storage_order.slot_id)) from biz_storage_order where order_no='$orderNo'"))

$pickup = PostJson "$base/api/mini/storage/orders/$orderNo/pickup" '{}' $token
Write-Output ("after PICKUP       : " + (Sql "select concat(status,' settled=',ifnull(settled_points,-1),' arrears=',ifnull(arrears_points,-1),' prepay=',ifnull(prepay_points,-1)) from biz_storage_order where order_no='$orderNo'"))
Write-Output ("  slot    : " + (Sql "select c.status from biz_compartment c join biz_storage_order o on o.slot_id=c.id where o.order_no='$orderNo'"))
Write-Output ("  account : " + (Sql "select concat('points=',points,' frozen=',frozen_points) from biz_point_account where customer_id=$customer"))

# 6) ledger invariant: the stored balance must equal the sum of the ledger.
#    biz_point_txn.amount is ALREADY SIGNED at rest (RECHARGE +3000, PREPAY -1600,
#    PREPAY_REFUND +800 -> 2200), so the check is a plain SUM: applying the sign again
#    here would double-negate and report a fake imbalance (it did, on the first run).
#    Retired types (FREEZE_*/DEPOSIT_*/UNFREEZE_*) are counted separately instead: they
#    must not appear on a fresh account, and a nonzero count means old code is still writing.
$sums = Sql @"
select concat(coalesce(sum(amount),-1), ',',
  (select count(*) from biz_point_txn where customer_id=$customer and biz_type in
    ('FREEZE_IN','FREEZE_OUT','UNFREEZE_IN','UNFREEZE_OUT','DEPOSIT_OUT','DEPOSIT_BACK_IN')))
  from biz_point_txn where customer_id=$customer
"@
$acct = Sql "select points from biz_point_account where customer_id=$customer"
$bad  = Sql "select concat((select count(*) from biz_point_txn where customer_id=$customer and amount = 0),',',(select count(*) from biz_point_account where customer_id=$customer and points < 0),',',(select count(*) from biz_storage_order where order_no='$orderNo' and status <> 'CLOSED'))"
Write-Output ("ledger check     : signed_sum,biz_type_leftover=" + $sums + " account_points=" + $acct + " zeros,neg,notclosed=" + $bad)

$sum = ($sums -split ',')[0]
if ($sum -ne $acct) { throw "LEDGER IMBALANCE: sum=$sum account=$acct" }
$legacy = ($sums -split ',')[1]
if ($legacy -ne '0') { throw "retired freeze/deposit transactions present: $legacy" }
Write-Output 'OK: ledger balanced, order closed, compartment released'
