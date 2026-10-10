# ASCII-only script. Chinese literals come from a UTF-8 JSON pair list so PowerShell 5.1
# never has to parse non-ASCII source (which would be decoded as GBK and corrupt the text).
$ErrorActionPreference = 'Stop'
$root = 'C:/Users/lrs/Desktop/work/Cjagent'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$json = [System.IO.File]::ReadAllText("$root/tools/rename-terms.json", [System.Text.Encoding]::UTF8)
$pairs = ConvertFrom-Json $json

$files = @(
  'admin/index.html',
  'admin/package-lock.json',
  'admin/src/layouts/BasicLayout.vue',
  'admin/src/router/index.ts',
  'admin/src/views/LoginView.vue',
  'mini/index.html',
  'mini/package.json',
  'mini/package-lock.json',
  'mini/src/manifest.json',
  'mini/src/pages.json',
  'mini/src/pages/home/index.vue',
  'mini/src/pages/scan/index.vue',
  'mini/src/pages/mine/index.vue',
  'mini/src/api/http.ts',
  'mini/eslint.config.js',
  'server/src/main/java/com/wherelee/cabinet/application/auth/TenantGuard.java',
  'server/src/main/java/com/wherelee/cabinet/config/SecurityConfig.java',
  'server/src/main/java/com/wherelee/cabinet/domain/entity/BizCustomer.java',
  'server/src/main/java/com/wherelee/cabinet/infrastructure/security/AuthoritiesResolver.java',
  'server/src/main/java/com/wherelee/cabinet/infrastructure/security/JwtTokenService.java',
  'server/src/main/java/com/wherelee/cabinet/infrastructure/security/TokenIssuer.java',
  'server/src/main/java/com/wherelee/cabinet/interfaces/mini/auth/MiniAuthController.java',
  'server/src/main/java/com/wherelee/cabinet/interfaces/mini/auth/dto/MiniRefreshRequest.java',
  'server/src/main/java/com/wherelee/cabinet/interfaces/mini/auth/dto/MiniTokenView.java',
  'server/src/test/java/com/wherelee/cabinet/AdminAuthFlowTest.java',
  'server/src/test/java/com/wherelee/cabinet/ArchitectureTest.java',
  '.github/workflows/ci.yml',
  'docs/页面流转.md',
  'server/pom.xml',
  'server/src/main/java/com/wherelee/cabinet/CabinetServerApplication.java',
  'server/src/main/java/com/wherelee/cabinet/config/OpenApiConfig.java',
  'server/src/main/java/com/wherelee/cabinet/common/mask/MaskType.java'
)

foreach ($f in $files) {
  $p = Join-Path $root $f
  if (-not (Test-Path $p)) { Write-Output "SKIP missing $f"; continue }
  $t = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
  $before = $t
  foreach ($pair in $pairs) {
    $t = $t.Replace([string]$pair[0], [string]$pair[1])
  }
  if ($t -ne $before) {
    [System.IO.File]::WriteAllText($p, $t, $utf8)
    Write-Output "FIXED $f"
  } else {
    Write-Output "same  $f"
  }
}
