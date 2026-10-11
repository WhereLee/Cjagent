# ASCII only; Chinese literals come from the UTF-8 JSON pair file (PowerShell 5.1 would
# otherwise read this script as GBK and corrupt any non-ASCII text).
$ErrorActionPreference = 'Stop'
$root = 'C:/Users/lrs/Desktop/work/Cjagent'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$pairs = ConvertFrom-Json ([System.IO.File]::ReadAllText("$root/tools/sweep-19d.json", [System.Text.Encoding]::UTF8))

$files = @(
  'server/src/test/java/com/wherelee/cabinet/DoorAndContentFlowTest.java',
  'server/src/test/java/com/wherelee/cabinet/ItemDisputeFlowTest.java',
  'server/src/test/java/com/wherelee/cabinet/SingleSlotAndVoucherTest.java',
  'server/src/test/java/com/wherelee/cabinet/ArrearsRepayFlowTest.java',
  'server/src/test/java/com/wherelee/cabinet/AdminLockerConsoleTest.java',
  'server/src/test/java/com/wherelee/cabinet/PlaceAndReconcileTest.java',
  'server/src/main/java/com/wherelee/cabinet/application/task/TaskHandlers.java',
  'server/src/main/java/com/wherelee/cabinet/application/storage/CompartmentStateService.java'
)

foreach ($f in $files) {
  $p = Join-Path $root $f
  if (-not (Test-Path $p)) { Write-Output "skip $f"; continue }
  $t = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
  $o = $t
  foreach ($pair in $pairs) { $t = $t.Replace([string]$pair[0], [string]$pair[1]) }
  if ($t -ne $o) {
    [System.IO.File]::WriteAllText($p, $t, $utf8NoBom)
    Write-Output "fixed $f"
  } else { Write-Output "same  $f" }
}
