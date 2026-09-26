$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$nodeExecutable = if ($nodeCommand) { $nodeCommand.Source } else {
    Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\OpenJS.NodeJS.LTS_*\node-v*-win-x64\node.exe" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $nodeExecutable) { throw '未找到 Node.js，请先安装 Node.js 22 或以上版本。' }
$env:PATH = (Split-Path -Parent $nodeExecutable) + ';' + $env:PATH
$env:CLOUDFLARED_PATH = Join-Path $PSScriptRoot 'tools\cloudflared.exe'
$env:CONNECTION_OUTPUT = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\手机连接'))
$env:PUBLIC_APK_PATH = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\奶蛙照相馆-v1.4.0-多人试用.apk'))
if (-not (Test-Path -LiteralPath $env:CLOUDFLARED_PATH)) { throw '缺少 tools\cloudflared.exe，请完整解压自动连接试用包。' }
if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
if (-not (Test-Path -LiteralPath 'node_modules/@openai/codex') -or -not (Test-Path -LiteralPath 'node_modules/qrcode')) {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw '服务依赖安装未完成，请重新运行。' }
}
Write-Host '奶蛙照相馆 · 自动连接' -ForegroundColor Green
Write-Host '手机可以使用 Wi-Fi、流量或 VPN。连接码会保存到“手机连接”文件夹。'
Write-Host '请保持服务窗口运行。'
& $nodeExecutable --env-file-if-exists=.env src/connect.mjs
if ($LASTEXITCODE -ne 0) { throw "连接服务退出，退出码：$LASTEXITCODE。请查看上方信息。" }
