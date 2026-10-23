param([Parameter(Mandatory)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporary = Join-Path $projectRoot '.build/installer-helper'
New-Item -ItemType Directory -Force $temporary | Out-Null
$source = Join-Path $projectRoot 'packaging/installer-helper/main.c'
if (Get-Command cl.exe -ErrorAction SilentlyContinue) {
    & cl.exe /nologo /utf-8 /O1 /MT "/Fo$(Join-Path $temporary 'helper.obj')" $source /link /SUBSYSTEM:WINDOWS "/OUT:$OutputPath" advapi32.lib shell32.lib ole32.lib uuid.lib kernel32.lib
} elseif (Get-Command gcc.exe -ErrorAction SilentlyContinue) {
    & gcc.exe -municode -mwindows -Os -static -s $source -o $OutputPath -ladvapi32 -lshell32 -lole32 -luuid -lkernel32
} else { throw 'Use a Visual Studio C++ developer shell, or install MinGW-w64.' }
if ($LASTEXITCODE -ne 0) { throw 'Installer helper compilation failed.' }
