param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$bash = (Get-Command bash -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if ($IsWindows -and (Test-Path "$env:ProgramFiles/Git/bin/bash.exe")) { $bash = "$env:ProgramFiles/Git/bin/bash.exe" }
if (-not $bash) { throw 'Install Git Bash or run bash scripts/build-android-audio.sh on Linux before building.' }
if ($IsWindows -and -not $env:MAKE) {
    $cache = Join-Path $projectRoot '.build/audio'
    [void][IO.Directory]::CreateDirectory($cache)
    $make = Join-Path $cache 'usr/bin/make.exe'
    if (-not (Test-Path -LiteralPath $make)) {
        $package = Join-Path $cache 'msys-make.pkg.tar.zst'
        Invoke-WebRequest 'https://repo.msys2.org/msys/x86_64/make-4.4.1-3-x86_64.pkg.tar.zst' -OutFile $package
        if ((Get-FileHash $package).Hash -ne 'AF0BDBA17F06FE037F0194069ADAA31A8FE45F1A11381501896AEA1FAE37BD5D') { throw 'GNU Make checksum mismatch.' }
        tar.exe -xf $package -C $cache usr/bin/make.exe
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract GNU Make.' }
    }
    $env:MAKE = (& $bash -c 'cygpath -u "$1"' -- $make)
}
& $bash (Join-Path $PSScriptRoot 'build-android-audio.sh')
if ($LASTEXITCODE -ne 0) { throw 'Android audio processor build failed.' }
Push-Location (Join-Path $projectRoot 'android')
try {
    & .\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    $outputDirectory = Join-Path $projectRoot 'artifacts/android'
    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination (Join-Path $outputDirectory 'BiliDownload-0.4.0-preview.apk') -Force
} finally { Pop-Location }
