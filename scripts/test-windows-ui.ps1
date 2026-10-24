param([string]$PackageDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PackageDirectory) { $PackageDirectory = Join-Path $projectRoot 'artifacts/windows-0.1.6' }
$results = Join-Path $projectRoot ('.build/ui-validation/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $results | Out-Null
$cases = @(@(480,'completed','Completed'),@(600,'completed','Completed'),@(900,'completed','Completed'),@(1180,'completed','Completed'),@(480,'queue','Queued'),@(480,'queue','Downloading'),@(480,'queue','Merging'),@(480,'queue','Paused'),@(480,'queue','Failed'),@(480,'queue','Canceled'),@(360,'completed','Completed'),@(360,'queue','Failed'),@(900,'settings','Completed'),@(480,'home','Completed','Light'),@(900,'settings','Completed','Light'),@(600,'completed','Completed','Light'),@(480,'home','Completed','Dark'))
foreach ($case in $cases) {
    $report = Join-Path $results ($case -join '-')
    $report += '.txt'
    $arguments = @('--smoke-test', "`"$report`"", $case[0], $case[1], $case[2], $(if($case.Count -gt 3){$case[3]}else{"Default"}))
    $launcher = Start-Process -FilePath (Join-Path $PackageDirectory 'BiliDownload.exe') -ArgumentList $arguments -WorkingDirectory $env:TEMP -WindowStyle Hidden -PassThru
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path -LiteralPath $report) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path -LiteralPath $report) -or -not ((Get-Content -Raw -LiteralPath $report).StartsWith('WinUI window initialized successfully'))) { throw "UI smoke failed: $report" }
    Write-Output "PASS $($case -join ' ')"
}
Write-Output "Screenshots and layout checks: $results"
