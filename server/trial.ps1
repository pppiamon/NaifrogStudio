$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$nodeExecutable = if ($nodeCommand) { $nodeCommand.Source } else {
    Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\OpenJS.NodeJS.LTS_*\node-v*-win-x64\node.exe" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $nodeExecutable) { throw '未找到 Node.js，请先安装 Node.js 22 或以上版本。' }
$env:PATH = (Split-Path -Parent $nodeExecutable) + ';' + $env:PATH
if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env' }
$envLines = Get-Content -LiteralPath '.env'
$portLine = $envLines | Where-Object { $_ -match '^\s*PORT\s*=' } | Select-Object -Last 1
$servicePort = if ($portLine) { [int](($portLine -split '=', 2)[1].Trim()) } else { 8787 }
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(3)
$alreadyRunning = $false
try {
    $health = $client.GetStringAsync("http://127.0.0.1:$servicePort/health").GetAwaiter().GetResult() | ConvertFrom-Json
    $alreadyRunning = $health.status -eq 'ok' -and $null -ne $health.providers
} catch { } finally { $client.Dispose(); $handler.Dispose() }
Write-Host ''
Write-Host '奶蛙照相馆 · 个人试用' -ForegroundColor Green
Write-Host '手机与电脑连接同一 Wi-Fi，App 的连接码留空（如自行设置过则填写原连接码）。'
Write-Host '在 App 设置中填写下方地址，点击“测试连接”，成功后保存并登录 Codex。'
foreach ($network in Get-NetIPConfiguration) {
    if ($network.IPv4DefaultGateway) {
        foreach ($ip in $network.IPv4Address) { Write-Host ("手机生成服务地址（{0}）：http://{1}:{2}" -f $network.InterfaceAlias, $ip.IPAddress, $servicePort) -ForegroundColor Cyan }
    }
}
Write-Host ''
if ($alreadyRunning) {
    Write-Host '生成服务已经运行。请保持原来的服务窗口打开。' -ForegroundColor Green
    exit 0
}
if (-not (Test-Path -LiteralPath 'node_modules/@openai/codex')) {
    Write-Host '首次运行，正在安装服务依赖…'
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw '服务依赖安装未完成，请检查电脑网络后重新运行。' }
}
Write-Host '正在启动服务。试用期间保持此窗口打开，按 Ctrl+C 可停止。'
& $nodeExecutable --env-file-if-exists=.env src/server.mjs
if ($LASTEXITCODE -ne 0) { throw "服务退出，退出码：$LASTEXITCODE。请查看上方错误信息。" }
