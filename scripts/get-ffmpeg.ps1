$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $projectRoot 'packaging/ffmpeg.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$directory = Join-Path $projectRoot ".build/ffmpeg/$($manifest.version)"
$tools = Join-Path $directory 'tools'
$requiredFiles = @('ffmpeg.exe','LICENSE.txt','ffmpeg.json','FFmpeg-source.tar.gz','BUILD-CONFIGURATION.txt','SOURCE-INFORMATION.txt')
if (($requiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $tools $_)) }).Count -eq 0) { Write-Output $tools; return }
New-Item -ItemType Directory -Force $directory | Out-Null
$archive = Join-Path $directory 'ffmpeg.zip'
Invoke-WebRequest $manifest.url -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256) { throw 'FFmpeg checksum mismatch.' }
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $directory 'extracted') -Force
$executable = Get-ChildItem (Join-Path $directory 'extracted') -Recurse -File -Filter ffmpeg.exe | Select-Object -First 1
if (-not $executable) { throw 'Downloaded FFmpeg archive does not contain ffmpeg.exe.' }
New-Item -ItemType Directory -Force $tools | Out-Null
Copy-Item -LiteralPath $executable.FullName -Destination $tools
$license = Get-ChildItem (Join-Path $directory 'extracted') -Recurse -File -Filter LICENSE.txt | Select-Object -First 1
Copy-Item -LiteralPath $license.FullName,$manifestPath -Destination $tools
Invoke-WebRequest $manifest.source -OutFile (Join-Path $tools 'FFmpeg-source.tar.gz')
& $executable.FullName -version | Set-Content (Join-Path $tools 'BUILD-CONFIGURATION.txt')
@('Build recipes and dependency sources: https://github.com/BtbN/FFmpeg-Builds','FFmpeg matching source and original license accompany this directory.') | Set-Content (Join-Path $tools 'SOURCE-INFORMATION.txt')
Write-Output $tools
