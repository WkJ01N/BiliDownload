$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $projectRoot 'packaging/ffmpeg-minimal.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$directory = Join-Path $projectRoot ".build/ffmpeg/$($manifest.version)"
$output = Join-Path $directory 'tools'
if (Test-Path -LiteralPath (Join-Path $output 'complete.json')) { Write-Output $output; return }
New-Item -ItemType Directory -Force $directory | Out-Null
$archive = Join-Path $directory 'source.tar.gz'
# Reuse only the verified archive from the preceding preview, when present.
$cachedArchive = Join-Path $projectRoot '.build/ffmpeg/n8.1.3-23-g5c4f5744cd/tools/FFmpeg-source.tar.gz'
if (-not (Test-Path -LiteralPath $archive)) {
    if (Test-Path -LiteralPath $cachedArchive) { Copy-Item -LiteralPath $cachedArchive -Destination $archive }
    else { Invoke-WebRequest $manifest.source -OutFile $archive }
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sourceSha256) { throw 'FFmpeg source checksum mismatch.' }
$bash = (Get-Command bash.exe -ErrorAction SilentlyContinue).Source
if (-not $bash) { $bash = Join-Path $env:ProgramFiles 'Git/bin/bash.exe' }
if (-not (Test-Path -LiteralPath $bash)) { throw 'Install MSYS2 or Git Bash, MinGW-w64 GCC, GNU Make and binutils to build FFmpeg.' }
$buildScript = Join-Path $PSScriptRoot 'build-ffmpeg.sh'
# Forward paths as separate arguments; Bash does not evaluate user paths as shell source.
$log = Join-Path $directory 'build.log'
Write-Host 'Building minimal FFmpeg from verified source...'
& $bash --noprofile --norc $buildScript $archive $directory $output $manifest.sourceVersion *> $log
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log -Tail 20 | Write-Host; throw 'Minimal FFmpeg build failed.' }
Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $output 'complete.json')
Write-Output $output
