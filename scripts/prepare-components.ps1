# Run when preparing a new component release; normal builds use the pinned published archives.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$minimal = & (Join-Path $PSScriptRoot 'build-minimal-ffmpeg.ps1')
$full = & (Join-Path $PSScriptRoot 'get-ffmpeg.ps1')
$output = Join-Path $projectRoot 'artifacts/components'
New-Item -ItemType Directory -Force $output | Out-Null
$packages = @(foreach($entry in @(
    @{Id='ffmpeg-minimal';Name='精简版 FFmpeg（推荐）';Version='8.1.3-minimal-1';Directory=$minimal},
    @{Id='ffmpeg-full';Name='完整版 FFmpeg（通用工具）';Version='8.1.3-5c4f5744cd';Directory=$full}
)) {
    $archive = Join-Path $output "$($entry.Id)-win-x64.zip"
    Compress-Archive -Path (Join-Path $entry.Directory '*') -DestinationPath $archive -Force
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{
        Id=$entry.Id;Name=$entry.Name;Version=$entry.Version
        Url="https://github.com/WkJ01N/BiliDownload/releases/download/v0.1.2/$($entry.Id)-win-x64.zip"
        Sha256=$hash;DownloadBytes=(Get-Item -LiteralPath $archive).Length
        InstalledBytes=[long](Get-ChildItem -LiteralPath $entry.Directory -Recurse -File | Measure-Object Length -Sum).Sum
    }
})
$packages | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $projectRoot 'src/BiliDownload.Windows/Assets/components.json') -Encoding utf8
Write-Output 'Component archives and pinned download manifest prepared.'
