$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ('.build/installer-validation/' + [Guid]::NewGuid().ToString('N'))
$installDirectory = Join-Path $testRoot '中文路径 with spaces'
New-Item -ItemType Directory -Force $testRoot | Out-Null
$compiler = & (Join-Path $PSScriptRoot 'install-inno.ps1')
$package = Join-Path $projectRoot 'artifacts/windows-0.1.6'
$helper = Join-Path $projectRoot '.build/installer-helper/PrepareDirectory.exe'
$full = & (Join-Path $PSScriptRoot 'get-component.ps1') -Id ffmpeg-full
& $compiler /Q "/DPackageDir=$package" "/DFullFfmpegDir=$full" "/DDirectoryHelper=$helper" "/DOutputDir=$testRoot" /DApplicationId=BiliDownload.WkJ01N.QA /DTestMode=1 (Join-Path $projectRoot 'packaging/windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'QA installer compilation failed.' }
$installer = Join-Path $testRoot 'BiliDownload-0.1.6-windows-x64-setup.exe'
function Install-QA([string]$type = 'recommended') {
    $process = Start-Process -FilePath $installer -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/TYPE=$type","/DIR=`"$installDirectory`"") -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw 'QA installation failed.' }
}
$oldPackage = Join-Path $projectRoot 'artifacts/windows-0.1.2'
if (Test-Path -LiteralPath (Join-Path $oldPackage 'BiliDownload.exe')) {
    $oldScript = Join-Path $testRoot 'old-windows.iss'
    git -C $projectRoot show db00b51:packaging/windows.iss | Set-Content -LiteralPath $oldScript -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging/windows-obsolete-0.1.1.iss') -Destination $testRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not read prior installer configuration.' }
    & $compiler /Q "/DPackageDir=$oldPackage" "/DFullFfmpegDir=$full" "/DOutputDir=$testRoot" /DApplicationId=BiliDownload.WkJ01N.QA /DTestMode=1 $oldScript
    if ($LASTEXITCODE -ne 0) { throw 'Prior installer compilation failed.' }
    $prior = Join-Path $testRoot 'BiliDownload-0.1.2-windows-x64-setup.exe'
    $process = Start-Process -FilePath $prior -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=`"$installDirectory`"") -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw 'Prior version installation failed.' }
    Install-QA
    foreach($obsolete in @('onnxruntime.dll','DirectML.dll','Microsoft.Windows.Widgets.dll')) {
        if (Test-Path -LiteralPath (Join-Path $installDirectory "app/$obsolete")) { throw "Obsolete SDK component retained: $obsolete" }
    }
    Write-Output 'PASS 0.1.2 to 0.1.6 upgrade preserves the previous directory'
} else { Install-QA; Write-Output 'SKIP previous-version upgrade: prior local payload not present' }
$ffmpeg = Join-Path $installDirectory 'app/tools/ffmpeg.exe'
if ((Get-Item -LiteralPath $ffmpeg).Length -gt 5MB) { throw 'Recommended setup did not select minimal FFmpeg.' }
$userData = Join-Path $testRoot 'user-data'
$videoDirectory = Join-Path $installDirectory 'my-videos'
New-Item -ItemType Directory -Force $userData,$videoDirectory | Out-Null
$settings = Join-Path $userData 'settings.json'
$history = Join-Path $userData 'tasks.db'
$video = Join-Path $videoDirectory 'keep.mp4'
Set-Content -LiteralPath $settings -Value 'keep-settings'
Set-Content -LiteralPath $history -Value 'keep-history'
Set-Content -LiteralPath $video -Value 'keep-video'
Install-QA
& (Join-Path $PSScriptRoot 'test-windows-ui.ps1') -PackageDirectory $installDirectory
Install-QA 'full'
if ((Get-Item -LiteralPath $ffmpeg).Length -lt 50MB) { throw 'Full FFmpeg was not installed.' }
Install-QA 'core'
if ((Test-Path -LiteralPath $ffmpeg) -or (Test-Path -LiteralPath (Join-Path $installDirectory 'docs/ffmpeg/FFmpeg-source.tar.gz'))) { throw 'No-FFmpeg setup retained the bundled component.' }
$uninstaller = Join-Path $installDirectory 'app/uninstall/unins000.exe'
if (-not (Test-Path -LiteralPath $uninstaller)) { throw 'Uninstaller is not in the payload subdirectory.' }
$process = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw 'QA uninstall failed.' }
foreach ($file in @($settings,$history,$video)) { if (-not (Test-Path -LiteralPath $file)) { throw "User file was removed: $file" } }
if (Test-Path -LiteralPath (Join-Path $installDirectory 'BiliDownload.exe')) { throw 'Installed application was not removed.' }
Write-Output 'PASS minimal/full/no-FFmpeg install, upgrade selection, Unicode path, launcher, uninstall, and user file preservation'
