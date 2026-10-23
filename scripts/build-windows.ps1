param([string]$FfmpegDirectory, [switch]$SkipInstaller)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$version = '0.1.5'
$env:DOTNET_CLI_HOME = Join-Path $projectRoot '.build/dotnet'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:MSBuildEnableWorkloadResolver = 'false'
$env:NUGET_PACKAGES = Join-Path $projectRoot '.build/packages'
$outputDirectory = Join-Path $projectRoot "artifacts/windows-$version"
# Only delete this script's generated, versioned output after verifying the absolute path.
$expected = [IO.Path]::GetFullPath((Join-Path $projectRoot "artifacts/windows-$version"))
if ([IO.Path]::GetFullPath($outputDirectory) -ne $expected -or -not $expected.StartsWith([IO.Path]::GetFullPath($projectRoot) + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe output directory.' }
if (Test-Path -LiteralPath $outputDirectory) { Remove-Item -LiteralPath $outputDirectory -Recurse -Force }
$payload = Join-Path $outputDirectory 'app'
$docs = Join-Path $outputDirectory 'docs'
New-Item -ItemType Directory -Force $payload,$docs | Out-Null
dotnet publish (Join-Path $projectRoot 'src/BiliDownload.Windows/BiliDownload.Windows.csproj') -c Release -r win-x64 -p:Platform=x64 -o $payload
if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
if (-not (Test-Path -LiteralPath (Join-Path $payload 'BiliDownload.pri'))) { throw 'WinUI resource index is missing from publish output.' }
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE'),(Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md'),(Join-Path $projectRoot 'README.md'),(Join-Path $projectRoot 'VALIDATION.md') -Destination $docs
Copy-Item -LiteralPath (Join-Path $projectRoot 'branding') -Destination $docs -Recurse
if (-not $FfmpegDirectory) { $FfmpegDirectory = & (Join-Path $PSScriptRoot 'get-component.ps1') -Id ffmpeg-minimal }
$sourceDirectory = (Resolve-Path -LiteralPath $FfmpegDirectory).Path
$executable = Join-Path $sourceDirectory 'ffmpeg.exe'
if (-not (Test-Path -LiteralPath $executable)) { throw 'FfmpegDirectory must contain ffmpeg.exe.' }
if (((& $executable -version) -join "`n") -match '--enable-gpl|--enable-nonfree') { throw 'An LGPL FFmpeg build is required.' }
$toolsDirectory = Join-Path $payload 'tools'
$licenseDirectory = Join-Path $docs 'ffmpeg'
New-Item -ItemType Directory -Force $toolsDirectory,$licenseDirectory | Out-Null
foreach ($file in Get-ChildItem -LiteralPath $sourceDirectory) {
    if ($file.Name -eq 'ffprobe.exe' -or $file.Name -eq 'ffplay.exe') { continue }
    if ($file.Name -eq 'ready.sha256') { continue }
    if ($file.Extension -in '.exe','.dll') { Copy-Item -LiteralPath $file.FullName -Destination $toolsDirectory }
    else { Copy-Item -LiteralPath $file.FullName -Destination $licenseDirectory -Recurse }
}
& (Join-Path $PSScriptRoot 'build-launcher.ps1') -OutputPath (Join-Path $outputDirectory 'BiliDownload.exe')
Compress-Archive -Path (Join-Path $outputDirectory '*') -DestinationPath (Join-Path $projectRoot "artifacts/BiliDownload-$version-windows-x64-portable.zip") -Force
if (-not $SkipInstaller) {
    $helper = Join-Path $projectRoot '.build/installer-helper/PrepareDirectory.exe'
    & (Join-Path $PSScriptRoot 'build-installer-helper.ps1') -OutputPath $helper
    $fullFfmpegDirectory = & (Join-Path $PSScriptRoot 'get-component.ps1') -Id ffmpeg-full
    $compiler = & (Join-Path $PSScriptRoot 'install-inno.ps1')
    & $compiler /Q "/DPackageDir=$outputDirectory" "/DFullFfmpegDir=$fullFfmpegDirectory" "/DDirectoryHelper=$helper" "/DOutputDir=$(Join-Path $projectRoot 'artifacts')" "/DAppVersion=$version" (Join-Path $projectRoot 'packaging/windows.iss')
    if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
}
Write-Output "Windows package: $outputDirectory"
