$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ('.build/installer-validation/' + [Guid]::NewGuid().ToString('N'))
$installDirectory = Join-Path $testRoot '中文路径 with spaces'
New-Item -ItemType Directory -Force $testRoot | Out-Null
$compiler = & (Join-Path $PSScriptRoot 'install-inno.ps1')
$package = Join-Path $projectRoot 'artifacts/windows-0.1.1'
& $compiler /Q "/DPackageDir=$package" "/DOutputDir=$testRoot" /DApplicationId=BiliDownload.WkJ01N.QA /DTestMode=1 (Join-Path $projectRoot 'packaging/windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'QA installer compilation failed.' }
$installer = Join-Path $testRoot 'BiliDownload-0.1.1-windows-x64-setup.exe'
function Install-QA {
    $process = Start-Process -FilePath $installer -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=`"$installDirectory`"") -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw 'QA installation failed.' }
}
Install-QA
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
$uninstaller = Join-Path $installDirectory 'app/uninstall/unins000.exe'
if (-not (Test-Path -LiteralPath $uninstaller)) { throw 'Uninstaller is not in the payload subdirectory.' }
$process = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw 'QA uninstall failed.' }
foreach ($file in @($settings,$history,$video)) { if (-not (Test-Path -LiteralPath $file)) { throw "User file was removed: $file" } }
if (Test-Path -LiteralPath (Join-Path $installDirectory 'BiliDownload.exe')) { throw 'Installed application was not removed.' }
Write-Output 'PASS per-user install, reinstall, Unicode path, launcher, uninstall, and user file preservation'
