param([string]$PackageDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PackageDirectory) { $PackageDirectory = Join-Path $projectRoot 'artifacts/windows-0.1.3' }
$names = (Get-ChildItem -LiteralPath $PackageDirectory | Sort-Object Name).Name -join ','
if ($names -ne 'app,BiliDownload.exe,docs') { throw "Unexpected publish root: $names" }
foreach ($relative in @('app/BiliDownload.pri','app/Assets/AppIcon.ico','app/tools/ffmpeg.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PackageDirectory $relative))) { throw "Missing package resource: $relative" }
}
$root = Join-Path $projectRoot ('.build/directory-helper-test/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $root | Out-Null
$target = Join-Path $root 'BiliDownload 中文路径'
$helper = Join-Path $projectRoot '.build/installer-helper/PrepareDirectory.exe'
function Prepare([string]$Path) {
    $process = Start-Process -FilePath $helper -ArgumentList @('--prepare',"`"$Path`"",$PID) -WindowStyle Hidden -Wait -PassThru
    return $process.ExitCode
}
if ((Prepare $target) -ne 0) { throw 'Directory preparation failed.' }
$owner = (Get-Acl -LiteralPath $target).GetOwner([Security.Principal.SecurityIdentifier]).Value
if ($owner -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) { throw 'Incorrect install-directory owner.' }
Set-Content -LiteralPath (Join-Path $target 'keep.txt') -Value keep
if ((Prepare $target) -ne 145) { throw 'Nonempty directory was accepted for privileged ACL propagation.' }
foreach ($forbidden in @([IO.Path]::GetPathRoot($projectRoot), [Environment]::GetFolderPath('ProgramFiles'))) {
    if ((Prepare $forbidden) -eq 0) { throw 'Shared parent or drive root was accepted.' }
}
$junction = Join-Path $root 'junction'
New-Item -ItemType Junction -Path $junction -Target $target | Out-Null
if ((Prepare (Join-Path $junction 'child')) -eq 0) { throw 'Junction target was accepted.' }
Write-Output 'PASS publish structure, directory creation, installing-user ownership, nonempty directory, protected parent, root and junction rejection'
