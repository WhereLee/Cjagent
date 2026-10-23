# smoke.ps1 - end-to-end smoke against a running (or freshly started) backend.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads a BOM-less file as GBK and
# Chinese comments break parsing. Chinese notes live in docs / README.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1                      # assume app already up
#   powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1 -StartServer         # start app, then smoke
#   powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1 -Biz                 # + login/place/door/pickup round
#   powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1 -Token "<admin jwt>" # + admin queues
#
# -Biz needs dev mock login (cabinet.mini.mock-login=true) and a seeded cabinet.
# Exit code is 0 only when every required step succeeds; the table prints per-step latency.

param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Token = $env:SMOKE_ADMIN_TOKEN,
    [switch]$StartServer,
    [switch]$Biz,
    [string]$TenantCode = 'platform',
    [string]$CabinetNo = 'CAB-001-3',
    [string]$SizeType = 'SMALL',
    # Allocator to run the chain under (pessimistic / cas / redisson / prealloc).
    # Empty = whatever application.yml says. Only used together with -StartServer.
    [string]$Strategy = '',
    [int]$ReadyTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$appProcess = $null
$failures = New-Object System.Collections.Generic.List[string]

function Write-Step {
    param([string]$Name, [bool]$Ok, [long]$Millis, [string]$Detail)
    $status = if ($Ok) { 'PASS' } else { 'FAIL' }
    $line = '{0,-30} {1,4}  {2,6} ms  {3}' -f $Name, $status, $Millis, $Detail
    # Write-Host, not Write-Output: the step line must not be captured by "$x = Invoke-..."
    Write-Host $line
    if (-not $Ok) { $failures.Add($Name) | Out-Null }
}

function Invoke-SmokeRequest {
    param(
        [string]$Name,
        [string]$Url,
        [string]$AuthToken,
        [string]$Method = 'GET',
        [object]$Body = $null,
        [switch]$Optional
    )
    $headers = @{}
    if ($AuthToken) { $headers['Authorization'] = "Bearer $AuthToken" }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $req = @{ Uri = $Url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30; Method = $Method }
        if ($null -ne $Body) {
            $req['ContentType'] = 'application/json'
            $req['Body'] = ($Body | ConvertTo-Json -Compress -Depth 6)
        }
        $resp = Invoke-WebRequest @req
        $sw.Stop()
        $parsed = $resp.Content | ConvertFrom-Json
        if ($null -ne $parsed.code -and $parsed.code -ne 0) {
            Write-Step -Name $Name -Ok $false -Millis $sw.ElapsedMilliseconds -Detail ("business code " + $parsed.code + ": " + $parsed.message)
            return $null
        }
        Write-Step -Name $Name -Ok $true -Millis $sw.ElapsedMilliseconds -Detail ("HTTP " + $resp.StatusCode)
        return $parsed
    } catch {
        $sw.Stop()
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        if ($Optional -and ($code -eq 401 -or $code -eq 403)) {
            Write-Step -Name $Name -Ok $true -Millis $sw.ElapsedMilliseconds -Detail ("SKIP (no token, HTTP " + $code + ")")
        } else {
            $detail = if ($code) { "HTTP $code" } else { $_.Exception.Message }
            Write-Step -Name $Name -Ok $false -Millis $sw.ElapsedMilliseconds -Detail $detail
        }
        return $null
    }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri "$BaseUrl/api/system/health" -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { return $true }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function New-RequestId {
    param([string]$Prefix)
    return "$Prefix-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

try {
    if ($StartServer) {
        $runApp = Join-Path $root 'scripts\run-app.ps1'
        Write-Output "starting backend from $runApp ..."
        # Forward the allocator so one smoke chain can be replayed under each strategy.
        # prealloc used to fail every place-order with HTTP 500 before batch 2; this is
        # the regression run that proves it stays fixed.
        $launchArgs = @('-ExecutionPolicy', 'Bypass', '-File', $runApp, '-Profile', 'dev')
        if ($Strategy) {
            $launchArgs += @('-AppArgs', "--cabinet.alloc.strategy=$Strategy")
        }
        $appProcess = Start-Process -FilePath 'powershell' `
            -ArgumentList $launchArgs `
            -WorkingDirectory $root -PassThru
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $healthy = Wait-Healthy
    $sw.Stop()
    if ($healthy) {
        Write-Step -Name 'system-health' -Ok $true -Millis $sw.ElapsedMilliseconds -Detail 'HTTP 200'
    } else {
        Write-Step -Name 'system-health' -Ok $false -Millis $sw.ElapsedMilliseconds -Detail 'backend not ready'
        throw 'backend is not healthy: see server/logs or the console output'
    }

    Invoke-SmokeRequest -Name 'openapi-docs' -Url "$BaseUrl/v3/api-docs" | Out-Null
    Invoke-SmokeRequest -Name 'admin-workorder-queue' -AuthToken $Token -Optional `
        -Url "$BaseUrl/api/admin/work-orders/online?openOnly=true&pageNum=1&pageSize=10" | Out-Null
    Invoke-SmokeRequest -Name 'admin-locker-ledger' -AuthToken $Token -Optional `
        -Url "$BaseUrl/api/admin/locker/anomalies?pageNum=1&pageSize=10" | Out-Null
    Invoke-SmokeRequest -Name 'admin-order-list' -AuthToken $Token -Optional `
        -Url "$BaseUrl/api/admin/orders?pageNum=1&pageSize=10" | Out-Null

    if ($Biz) {
        $suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $phone = '139' + ($suffix % 100000000).ToString('00000000')

        $login = Invoke-SmokeRequest -Name 'mini-login' -Method POST `
            -Url "$BaseUrl/api/mini/auth/login" `
            -Body @{ code = "smoke-$suffix"; tenantCode = $TenantCode; phone = $phone }
        $miniToken = $null
        if ($login -and $login.data) { $miniToken = $login.data.accessToken }
        if (-not $miniToken) {
            Write-Step -Name 'mini-token' -Ok $false -Millis 0 -Detail 'login did not return accessToken'
        } else {
            Write-Step -Name 'mini-token' -Ok $true -Millis 0 -Detail 'accessToken acquired'

            Invoke-SmokeRequest -Name 'mini-recharge' -Method POST -AuthToken $miniToken `
                -Url "$BaseUrl/api/mini/points/recharge" `
                -Body @{ requestId = (New-RequestId 'smoke-rc'); points = 10000 } | Out-Null

            $order = Invoke-SmokeRequest -Name 'mini-place-order' -Method POST -AuthToken $miniToken `
                -Url "$BaseUrl/api/mini/storage/orders" `
                -Body @{ requestId = (New-RequestId 'smoke-so'); cabinetNo = $CabinetNo
                         sizeType = $SizeType; estimateMinutes = 60 }
            $orderNo = $null
            if ($order -and $order.data) { $orderNo = $order.data.orderNo }
            if (-not $orderNo) {
                Write-Step -Name 'mini-order-no' -Ok $false -Millis 0 -Detail 'place did not return orderNo'
            } else {
                Write-Step -Name 'mini-order-no' -Ok $true -Millis 0 -Detail $orderNo

                Invoke-SmokeRequest -Name 'mini-close-verify' -Method POST -AuthToken $miniToken `
                    -Url "$BaseUrl/api/mini/storage/orders/$orderNo/door?action=CLOSE_VERIFY" | Out-Null

                $voucher = Invoke-SmokeRequest -Name 'mini-voucher' -AuthToken $miniToken `
                    -Url "$BaseUrl/api/mini/storage/orders/$orderNo/voucher"
                $voucherCode = $null
                if ($voucher -and $voucher.data) { $voucherCode = $voucher.data.voucherCode }

                Invoke-SmokeRequest -Name 'mini-pickup' -Method POST -AuthToken $miniToken `
                    -Url "$BaseUrl/api/mini/storage/orders/$orderNo/pickup" `
                    -Body @{ voucherCode = $voucherCode } | Out-Null
            }

            Invoke-SmokeRequest -Name 'mini-my-orders' -AuthToken $miniToken `
                -Url "$BaseUrl/api/mini/storage/orders?limit=10" | Out-Null
            Invoke-SmokeRequest -Name 'mini-work-orders' -AuthToken $miniToken `
                -Url "$BaseUrl/api/mini/work-orders?limit=10" | Out-Null
        }
    }

    if ($failures.Count -gt 0) {
        Write-Output ''
        Write-Output ("SMOKE FAILED: " + ($failures -join ', '))
        exit 1
    }
    Write-Output ''
    Write-Output 'SMOKE PASSED'
    exit 0
} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Write-Output "stopping backend pid=$($appProcess.Id) ..."
        & taskkill /PID $appProcess.Id /T /F | Out-Null
    }
}
