param([Parameter(Mandatory)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporary = Join-Path $projectRoot '.build/launcher'
New-Item -ItemType Directory -Force $temporary | Out-Null
$source = Join-Path $projectRoot 'packaging/launcher/main.c'
$icon = (Join-Path $projectRoot 'src/BiliDownload.Windows/Assets/AppIcon.ico').Replace('\','\\')
$resource = Join-Path $temporary 'app.rc'
"1 ICON `"$icon`"" | Set-Content -LiteralPath $resource -Encoding utf8
if (Get-Command cl.exe -ErrorAction SilentlyContinue) {
    & rc.exe /nologo "/fo$(Join-Path $temporary 'app.res')" $resource
    if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
    & cl.exe /nologo /utf-8 /O1 /MT "/Fo$(Join-Path $temporary 'launcher.obj')" $source (Join-Path $temporary 'app.res') /link /SUBSYSTEM:WINDOWS "/OUT:$OutputPath" user32.lib kernel32.lib
} elseif (Get-Command gcc.exe -ErrorAction SilentlyContinue) {
    & windres.exe $resource (Join-Path $temporary 'app.o')
    if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
    & gcc.exe -municode -mwindows -Os -static -s $source (Join-Path $temporary 'app.o') -o $OutputPath -luser32 -lkernel32
} else { throw 'Use a Visual Studio C++ developer shell, or install MinGW-w64 (gcc and windres).' }
if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed.' }
