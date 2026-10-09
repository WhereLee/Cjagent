# verify-funds.ps1 - end-to-end money flow check against a running dev server.
#
# Why a script: the flow needs login -> fund -> create -> open -> backdate -> close -> pickup
# plus SQL assertions, which is far too long for one inline command line.
# Keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less files as GBK).
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

# 1) login (token TTL is 30 min, so always mint a fresh one here)
$loginBody = @{ code = 'h5-funds-verifier'; tenantCode = 'platform' } | ConvertTo-Json -Compress
$loginFile = Join-Path $tmp 'funds-login.json'
[IO.File]::WriteAllText($loginFile, $loginBody, (New-Object Text.UTF8Encoding $false))
$login = curl.exe -s -X POST "$base/api/mini/auth/login" -H 'Content-Type: application/json' --data "@$loginFile" | ConvertFrom-Json
if (-not $login.data) { throw "login failed: $login" }
$token = $login.data.accessToken
$customer = $login.data.customerId
Write-Output "customer = $customer"

# 2) fund the account. There is no HTTP recharge endpoint yet (known gap, cut 13 adds
#    PayChannel + mock channel), so we insert a RECHARGE txn and then RECOMPUTE the account
#    from the transaction sum - same rule the application uses, so the ledger stays balanced
#    by construction instead of by a lucky constant.
Sql @"
insert into biz_point_txn (id,tenant_id,customer_id,biz_type,amount,balance_after,ref_type,biz_no,remark,create_time)
values (900000000002,1,$customer,'RECHARGE',3000,3000,'FIXTURE','FIXTURE-RECHARGE-$customer','verify script funding',now(3))
on duplicate key update amount = values(amount), balance_after = values(balance_after);
insert into biz_point_account (id,tenant_id,customer_id,points,frozen_points,version,create_time,update_time,deleted)
values (900000000001,1,$customer,0,0,0,now(3),now(3),0)
on duplicate key update update_time = now(3);
update biz_point_account a
  join (select customer_id,
          coalesce(sum(case when biz_type in ('RECHARGE','ADJUST','CONSUME','FREEZE_OUT','UNFREEZE_IN') then amount else 0 end),0) p,
          coalesce(sum(case when biz_type in ('FREEZE_IN','UNFREEZE_OUT') then amount else 0 end),0) f
          from biz_point_txn where customer_id = $customer group by customer_id) s
    on a.customer_id = s.customer_id
   set a.points = s.p, a.frozen_points = s.f, a.version = a.version + 1, a.update_time = now(3);
"@
Write-Output ("funded account: " + (Sql "select concat('points=',points,' frozen=',frozen_points) from biz_point_account where customer_id=$customer"))

# 2b) cancel any leftover active orders of this customer (from earlier runs) so the compartment
#     pool and the ledger both stay explainable
$left = Sql "select order_no from biz_storage_order where customer_id=$customer and active_flag = 1"
foreach ($l in @($left | Where-Object { $_ })) {
    $null = curl.exe -s --max-time 8 -X POST "$base/api/mini/storage/orders/$l/cancel" -H "Authorization: Bearer $token"
    Write-Output "cancelled leftover $l"
}

# 3) create order (MEDIUM, estimate 120 min)
$createBody = @{ requestId = [guid]::NewGuid().ToString(); cabinetNo = 'CAB-001-1'; sizeType = 'MEDIUM'; estimateMinutes = 120 } | ConvertTo-Json -Compress
$createFile = Join-Path $tmp 'funds-create.json'
[IO.File]::WriteAllText($createFile, $createBody, (New-Object Text.UTF8Encoding $false))
$created = curl.exe -s -X POST "$base/api/mini/storage/orders" -H 'Content-Type: application/json' -H "Authorization: Bearer $token" --data "@$createFile" | ConvertFrom-Json
if ($created.code -ne 0) { throw "create failed: $($created | ConvertTo-Json -Compress)" }
$orderNo = $created.data.orderNo
Write-Output ("created   : status=" + $created.data.status + " slot=" + $created.data.slotNo + " orderNo=$orderNo")
Write-Output ("  snapshot: " + (Sql "select pricing_snapshot from biz_storage_order where order_no='$orderNo'"))
Write-Output ("  after create : " + (Sql "select concat('order.frozen=',ifnull(frozen_points,-1),' deposit=',ifnull(deposit_points,-1)) from biz_storage_order where order_no='$orderNo'") + "  " + (Sql "select concat('acct points=',points,' frozen=',frozen_points) from biz_point_account where customer_id=$customer"))

# 4) open the door
$null = curl.exe -s -X POST "$base/api/mini/storage/orders/$orderNo/door?action=OPEN" -H "Authorization: Bearer $token"
Write-Output ("after OPEN       : " + (Sql "select status from biz_storage_order where order_no='$orderNo'"))

# 5) close the door, THEN backdate: CLOSE_VERIFY sets started_at = now (server time is the
#    billing anchor), so backdating before it would be overwritten and the CONSUME leg never runs.
$null = curl.exe -s -X POST "$base/api/mini/storage/orders/$orderNo/door?action=CLOSE_VERIFY" -H "Authorization: Bearer $token"
Write-Output ("after CLOSE_VERIFY : " + (Sql "select concat(status,' / slot=',(select status from biz_compartment c where c.id=biz_storage_order.slot_id)) from biz_storage_order where order_no='$orderNo'"))

# billing time must be backdated after that - time does not pass by itself in a script
Sql "update biz_storage_order set started_at = date_sub(now(3), interval 120 minute) where order_no='$orderNo'" | Out-Null
Write-Output ("started_at backdated to 120 min ago (expect billedHours=2 -> consume 2*25=50)")

# 6) pickup: settle + refund deposit + release compartment
$null = curl.exe -s -X POST "$base/api/mini/storage/orders/$orderNo/pickup" -H "Authorization: Bearer $token"
Write-Output ("after PICKUP       : " + (Sql "select concat(status,' settled=',ifnull(settled_points,-1),' arrears=',ifnull(arrears_points,-1),' frozen_left=',ifnull(frozen_points,-1)) from biz_storage_order where order_no='$orderNo'"))
Write-Output ("  slot  : " + (Sql "select c.status from biz_compartment c join biz_storage_order o on o.slot_id=c.id where o.order_no='$orderNo'"))
Write-Output ("  deposit: " + (Sql "select d.status from biz_deposit d join biz_storage_order o on o.id=d.order_id where o.order_no='$orderNo'"))
Write-Output ("  account: " + (Sql "select concat('points=',points,' frozen=',frozen_points) from biz_point_account where customer_id=$customer"))

$sums = Sql @"
select concat((select coalesce(sum(case when biz_type in ('RECHARGE','ADJUST','CONSUME','FREEZE_OUT','UNFREEZE_IN') then amount else 0 end),0) from biz_point_txn where customer_id=$customer),',',
  (select coalesce(sum(case when biz_type in ('FREEZE_IN','UNFREEZE_OUT') then amount else 0 end),0) from biz_point_txn where customer_id=$customer))
"@
$acct = Sql "select concat(points,',',frozen_points) from biz_point_account where customer_id=$customer"
$bad  = Sql "select concat((select count(*) from biz_point_txn where customer_id=$customer and amount = 0),',',(select count(*) from biz_point_account where customer_id=$customer and (points < 0 or frozen_points < 0)),',',(select count(*) from biz_storage_order where order_no='$orderNo' and status <> 'CLOSED'))"
Write-Output ("ledger check     : account=" + $acct + " sums=" + $sums + " (zeroAmountTxns,negativeCols,orderNotClosed)=" + $bad)
if ("$acct" -ne "$sums") { Write-Output 'RESULT: LEDGER IMBALANCED' } else { Write-Output 'RESULT: ledger balanced (independent SQL check)' }

$txns = Sql "select group_concat(concat(biz_type,':',amount) order by id) from biz_point_txn where ref_id = (select id from biz_storage_order where order_no='$orderNo')"
Write-Output ("order txns           : $txns")
