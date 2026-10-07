# run-app.ps1 - start the backend locally with the JVM options from deploy/jvm.env.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 decodes BOM-less files as GBK and
# Chinese comments break parsing. Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File scripts/run-app.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/run-app.ps1 -Profile dev
#
# JVM options come from deploy/jvm.opts so the local run and the future systemd unit
# cannot drift apart (single source of truth). Ctrl+C stops it.
# The file is deliberately NOT named *.env: in this project ".env" means "secrets, never
# committed", and a committed non-secret file must not collide with that rule or the
# pre-push scan pattern.

param(
    [string]$Profile = 'dev'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root 'deploy\jvm.opts'
$serverDir = Join-Path $root 'server'

$line = Get-Content $envFile | Where-Object { $_ -match '^JAVA_OPTS=' } | Select-Object -First 1
if (-not $line) {
    throw "JAVA_OPTS not found in $envFile"
}
$jvmArgs = $line.Substring('JAVA_OPTS='.Length).Trim()

# Heap dumps and gc.log both land under server/logs, which is gitignored.
New-Item -ItemType Directory -Force -Path (Join-Path $serverDir 'logs') | Out-Null

Write-Output "JVM: $jvmArgs"
Write-Output "profile: $Profile  workdir: $serverDir"

Push-Location $serverDir
try {
    & mvn -B -ntp spring-boot:run "-Dspring-boot.run.jvmArguments=$jvmArgs" "-Dspring-boot.run.profiles=$Profile"
} finally {
    Pop-Location
}
