param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
Push-Location (Join-Path $projectRoot 'android')
try {
    & .\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    $outputDirectory = Join-Path $projectRoot 'artifacts/android'
    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination (Join-Path $outputDirectory 'BiliDownload-0.1.5-preview.apk') -Force
} finally { Pop-Location }
