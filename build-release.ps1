param([string]$JavaHome, [string]$AndroidSdk, [string]$ServiceUrl = '', [string]$ConnectionFile = '')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk }
if (-not $env:JAVA_HOME) { throw '请使用 -JavaHome 指定 JDK 17 路径，或设置 JAVA_HOME。' }
if (-not $env:ANDROID_HOME) { throw '请使用 -AndroidSdk 指定 Android SDK 路径，或设置 ANDROID_HOME。' }
if (-not (Test-Path -LiteralPath 'signing.properties')) { throw '请把交付包中的 signing.properties 和 release-signing 放回对应目录。' }
$sdkPath = $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), ('sdk.dir=' + $sdkPath + [char]10))
$arguments = @('assembleRelease','bundleRelease','lintRelease')
if ($ServiceUrl) { $arguments += ('-PserviceUrl=' + $ServiceUrl) }
if ($ConnectionFile) { $arguments += ('-PconnectionFile=' + (Resolve-Path -LiteralPath $ConnectionFile).Path) }
& '.\gradlew.bat' @arguments
exit $LASTEXITCODE
