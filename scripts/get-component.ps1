param([Parameter(Mandatory)][ValidateSet('ffmpeg-minimal','ffmpeg-full')][string]$Id)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packages = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'src/BiliDownload.Windows/Assets/components.json') | ConvertFrom-Json
$package = $packages | Where-Object Id -eq $Id
if (-not $package) { throw 'Unknown component.' }
$directory = Join-Path $projectRoot ".build/component-packages/$Id/$($package.Sha256.Substring(0,12))"
$ready = Join-Path $directory 'ready.sha256'
if ((Test-Path -LiteralPath $ready) -and (Get-Content -Raw -LiteralPath $ready).Trim() -eq $package.Sha256 -and (Test-Path -LiteralPath (Join-Path $directory 'ffmpeg.exe'))) { Write-Output $directory; return }
$archive = Join-Path $projectRoot "artifacts/components/$Id-win-x64.zip"
New-Item -ItemType Directory -Force (Split-Path -Parent $archive) | Out-Null
if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.Sha256) {
    Invoke-WebRequest $package.Url -OutFile $archive -MaximumRetryCount 3 -RetryIntervalSec 3
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.Sha256) { throw 'Component archive checksum mismatch.' }
New-Item -ItemType Directory -Force $directory | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $directory -Force
if (-not (Test-Path -LiteralPath (Join-Path $directory 'ffmpeg.exe')) -or -not (Test-Path -LiteralPath (Join-Path $directory 'LICENSE.txt'))) { throw 'Component archive is incomplete.' }
Set-Content -LiteralPath $ready -Value $package.Sha256
Write-Output $directory
