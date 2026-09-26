param(
    [Parameter(Mandatory = $true)][string]$ServiceUrl,
    [string]$JavaHome,
    [string]$AndroidSdk
)
$ErrorActionPreference = 'Stop'
$releaseService = $ServiceUrl.Trim().TrimEnd('/')
$releaseUri = $null
if (-not [Uri]::TryCreate($releaseService, [UriKind]::Absolute, [ref]$releaseUri) -or
    $releaseUri.Scheme -ne 'https' -or $releaseUri.HostNameType -ne [UriHostNameType]::Dns -or
    $releaseUri.IsLoopback -or $releaseUri.UserInfo -or $releaseUri.Query -or $releaseUri.Fragment -or
    $releaseUri.AbsolutePath -ne '/') {
    throw '请填写已部署的 HTTPS 服务域名，例如 https://api.your-domain.com。'
}
$releaseService = $releaseUri.GetLeftPart([UriPartial]::Authority)
Write-Host "检查公网生成服务：$releaseService"
$serviceStatus = Invoke-RestMethod -Uri "$releaseService/v1/status" -TimeoutSec 30
if (-not $serviceStatus.ready -or -not $serviceStatus.providers.codex) {
    throw '请先在该地址启动奶蛙服务并启用 CODEX_ENABLED=true。'
}
Write-Host '服务连接成功，开始打包内置地址的 APK / AAB。'
& (Join-Path $PSScriptRoot 'build-release.ps1') -JavaHome $JavaHome -AndroidSdk $AndroidSdk -ServiceUrl $releaseService
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$releaseFolder = Join-Path $PSScriptRoot 'release'
New-Item -ItemType Directory -Path $releaseFolder -Force | Out-Null
$metadata = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
$releaseVersion = $metadata.elements[0].versionName
$releaseName = "奶蛙照相馆-v$releaseVersion-公网版"
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk') -Destination (Join-Path $releaseFolder "$releaseName.apk")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/bundle/release/app-release.aab') -Destination (Join-Path $releaseFolder "$releaseName.aab")
@{
    serviceUrl = $releaseService
    versionName = $releaseVersion
    versionCode = $metadata.elements[0].versionCode
    createdAt = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $releaseFolder '发布配置.json') -Encoding utf8
Write-Host "已生成：$releaseFolder"
Write-Host '新安装用户会自动使用内置服务地址。'
