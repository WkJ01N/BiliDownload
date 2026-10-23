param([string]$PackageDirectory)
# Run with pwsh -STA. The QA host and local HTTP fixtures never use real account data or Bilibili APIs.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes,System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BackgroundNative {
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr wp, IntPtr lp);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
 public static void ActivateNotification(string clsid,string args) {
   Guid classId=new Guid(clsid), iid=typeof(NotificationCallback).GUID;
   NotificationCallback instance;
   Marshal.ThrowExceptionForHR(CoCreateInstance(ref classId,IntPtr.Zero,4,ref iid,out instance));
   try { ((NotificationCallback)instance).Activate("BiliDownload",args,IntPtr.Zero,0); }
   finally { Marshal.ReleaseComObject(instance); }
 }
 [DllImport("ole32.dll")] private static extern int CoCreateInstance(ref Guid clsid,IntPtr outer,uint context,ref Guid iid,[MarshalAs(UnmanagedType.Interface)]out NotificationCallback instance);
}
[ComImport, Guid("53E31837-6600-4A81-9395-75CFFE746F94"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface NotificationCallback {
 void Activate([MarshalAs(UnmanagedType.LPWStr)]string appId,[MarshalAs(UnmanagedType.LPWStr)]string args,IntPtr data,uint count);
}
'@
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PackageDirectory) { $PackageDirectory = Join-Path $projectRoot 'artifacts/windows-0.1.5' }
$testRoot = Join-Path $projectRoot ('.build/background-validation/' + [Guid]::NewGuid().ToString('N'))
$appDirectory = Join-Path $testRoot 'app'
New-Item -ItemType Directory -Force $testRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $PackageDirectory 'app') -Destination $appDirectory -Recurse
$exe = Join-Path $appDirectory 'BiliDownload.QA.exe'
Copy-Item -LiteralPath (Join-Path $appDirectory 'BiliDownload.exe') -Destination $exe
Copy-Item -LiteralPath (Join-Path $appDirectory 'BiliDownload.pri') -Destination (Join-Path $appDirectory 'BiliDownload.QA.pri')
$full = & (Join-Path $PSScriptRoot 'get-component.ps1') -Id ffmpeg-full
& (Join-Path $full 'ffmpeg.exe') -hide_banner -loglevel error -f lavfi -i 'color=c=pink:s=64x64:d=0.3' -c:v h264_mf -hw_encoding 0 -pix_fmt nv12 (Join-Path $testRoot 'video.mp4')
if ($LASTEXITCODE -ne 0) { throw 'Could not generate QA media.' }
$server = Start-Process python -ArgumentList @('-u', "`"$(Join-Path $PSScriptRoot 'windows-fixture-server.py')`"", "`"$testRoot`"") -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $testRoot 'server-error.txt')
$qa = $null
$originalText = if ([Windows.Forms.Clipboard]::ContainsText()) { [Windows.Forms.Clipboard]::GetText() } else { $null }
$lastCopied = $null
function Wait-Until([scriptblock]$Check, [string]$Message) {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do { $value = & $Check; if ($value) { return $value }; Start-Sleep -Milliseconds 120 } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Message"
}
function Root { [System.Windows.Automation.AutomationElement]::FromHandle($script:qa.MainWindowHandle) }
function Find([string]$Name) {
    (Root).FindFirst([System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.OrCondition]::new(
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, $Name),
            [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $Name)))
}
function Invoke-Control([string]$Name) {
    $element = Wait-Until { Find $Name } "control $Name"
    $pattern = $null
    if ($element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) { $pattern.Invoke() }
    elseif ($element.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) { $pattern.Select() }
    else { throw "Control cannot be invoked: $Name" }
    Start-Sleep -Milliseconds 250
}
function Start-QA {
    $script:qa = Start-Process -FilePath $exe -WindowStyle Hidden -WorkingDirectory $env:TEMP -PassThru
    Wait-Until { $script:qa.Refresh(); $script:qa.MainWindowHandle -ne 0 } 'QA window' | Out-Null
    Wait-Until { Find 'HomePageTitle' } 'Home UI' | Out-Null
}
function Close-Window { [BackgroundNative]::PostMessage($script:qa.MainWindowHandle, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null }
function Minimize-ToTray { Close-Window; Invoke-Control '最小化到托盘'; Wait-Until { -not [BackgroundNative]::IsWindowVisible($script:qa.MainWindowHandle) } 'hidden tray window' | Out-Null }
function Forward([string]$Action, [string]$Id = '') {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value + '|' + [IO.Path]::GetFullPath((Join-Path $appDirectory 'qa-data')).ToUpperInvariant()
    $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identity))
    $name = 'BiliDownload-' + [Convert]::ToHexString($hash).Substring(0,24)
    $pipe = [IO.Pipes.NamedPipeClientStream]::new('.', $name, [IO.Pipes.PipeDirection]::InOut)
    try {
        $pipe.Connect(5000)
        $request = @{ Action=$Action }; if ($Id) { $request.Id=$Id }
        $bytes = [Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json -Compress) + "`n")
        $pipe.Write($bytes); if ($pipe.ReadByte() -ne 1) { throw 'No activation acknowledgement.' }
    } finally { $pipe.Dispose() }
}
function Copy-Link([string]$Link) {
    [Windows.Forms.Clipboard]::SetText('QA non-link ' + [Guid]::NewGuid()); Start-Sleep -Milliseconds 250
    [Windows.Forms.Clipboard]::SetText($Link); $script:lastCopied = $Link
    $file = Join-Path $appDirectory 'qa-data/pending-notifications.json'
    return (Wait-Until {
        if (Test-Path -LiteralPath $file) {
            @(Get-Content -Raw -LiteralPath $file | ConvertFrom-Json) | Where-Object { $_.Links -contains $Link } | Select-Object -Last 1
        }
    } 'clipboard snapshot').Id
}
function Tasks { $response = Invoke-RestMethod ($script:endpoint.TrimEnd('/') + '/qa/tasks'); foreach ($task in $response) { $task } }
function Native-Activation([string]$Id) {
    $registration = Get-ChildItem 'HKCU:\Software\Classes\CLSID' | Where-Object {
        $serverKey = Join-Path $_.PSPath 'LocalServer32'
        if (-not (Test-Path -LiteralPath $serverKey)) { return $false }
        $command = (Get-Item -LiteralPath $serverKey).GetValue('')
        return $command -is [string] -and $command.IndexOf([IO.Path]::GetFullPath($exe),[StringComparison]::OrdinalIgnoreCase) -ge 0
    } | Select-Object -First 1
    if (-not $registration) { throw 'Native notification COM server is not registered.' }
    Write-Output ("Native COM callback: " + $registration.PSChildName + '; merged registry=' + (Test-Path -LiteralPath ('Registry::HKEY_CLASSES_ROOT\CLSID\' + $registration.PSChildName + '\LocalServer32')))
    [BackgroundNative]::ActivateNotification($registration.PSChildName, 'request=' + $Id)
}
function Scroll-Settings([double]$Percent) {
    $all = (Root).FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($element in $all) {
        $pattern = $null
        if ($element.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern) -and $pattern.Current.VerticallyScrollable) {
            $pattern.SetScrollPercent(-1,$Percent)
        }
    }
}
function Set-Options([bool]$Auto, [bool]$Expand) {
    Forward 'open'; Invoke-Control '设置'; Start-Sleep -Milliseconds 300
    Scroll-Settings 75
    foreach ($entry in @(@('AutoDownloadSwitch',$Auto), @('ExpandRelatedSwitch',$Expand))) {
        $toggle = Wait-Until { Find $entry[0] } "setting $($entry[0])"
        $pattern = $toggle.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
        if (($pattern.Current.ToggleState -eq [System.Windows.Automation.ToggleState]::On) -ne $entry[1]) { $pattern.Toggle() }
    }
    Scroll-Settings 100
    $notificationNames = (Root).FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition) |
        ForEach-Object { $_.Current.Name } | Where-Object { $_ -match '系统通知可用|Windows 已关闭|系统通知注册失败|系统通知发送失败|无法读取系统通知' }
    Write-Output ('Notification runtime: ' + ($notificationNames -join ' / '))
    if ($notificationNames -match '注册失败|发送失败') { throw 'Native notification registration or delivery failed.' }
    $behavior = Wait-Until { Find 'CloseBehaviorBox' } 'close preference setting'
    $behavior.GetCurrentPattern([Windows.Automation.ExpandCollapsePattern]::Pattern).Expand()
    $ask = Wait-Until { Find '每次询问' } 'ask option'
    $ask.GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Select()
    $templateBox = Wait-Until { Find 'NamingTemplate' } 'naming template setting'
    $templateBox.GetCurrentPattern([Windows.Automation.ValuePattern]::Pattern).SetValue('{title}-模板-{index}')
    Invoke-Control '保存设置'; Start-Sleep -Milliseconds 200
}
try {
    Wait-Until { Test-Path -LiteralPath (Join-Path $testRoot 'endpoint.txt') } 'fixture server' | Out-Null
    $script:endpoint = (Get-Content -Raw -LiteralPath (Join-Path $testRoot 'endpoint.txt')).Trim()
    Set-Content -LiteralPath (Join-Path $appDirectory 'qa-endpoint.txt') -Value $endpoint
    Start-QA
    Close-Window; Invoke-Control '取消'
    if (-not [BackgroundNative]::IsWindowVisible($qa.MainWindowHandle)) { throw 'Cancel closed the window.' }
    Minimize-ToTray
    $id = Copy-Link 'https://www.bilibili.com/video/BV1xx411c7mD?p=2'
    Native-Activation $id
    $completed = Wait-Until { Tasks | Where-Object { $_.Item.Cid -eq 102 -and $_.State -eq 4 } } 'automatic P2 download and FFmpeg merge'
    if ($completed.OutputFileName -notmatch 'P2' -or $completed.OutputFileName -match '\[video:') { throw 'Automatic P2 filename did not use the full catalog.' }
    if (-not (Test-Path -LiteralPath $completed.FilePath)) { throw 'Merged output is missing.' }
    Forward 'notification' $id; Start-Sleep -Milliseconds 400
    if ((Tasks).Count -ne 1) { throw 'Repeated activation duplicated the download.' }
    Write-Output 'PASS close/cancel/tray, clipboard snapshot, activation, target P2, HTTP download, FFmpeg merge and replay protection'
    Set-Options $false $false
    Minimize-ToTray; $id = Copy-Link 'https://www.bilibili.com/video/BV1xx411c7mD'
    Forward 'notification' $id; Wait-Until { Find '下载选中内容' } 'confirmation page' | Out-Null
    Start-Sleep -Milliseconds 600
    if ((Tasks).Count -ne 1) { throw 'Disabled automatic download still added a task.' }
    Wait-Until { (Find 'SizeSummary').Current.Name -match '选中 1 项：约' } 'size estimate summary' | Out-Null
    Write-Output 'PASS quality size preview and selected-size summary'
    Write-Output 'PASS automatic download disabled: parse and confirm without queuing'
    Set-Options $true $true
    Minimize-ToTray; $id = Copy-Link 'https://www.bilibili.com/bangumi/play/ep202'
    Forward 'notification' $id; Wait-Until { Find '找到 2 个条目 · 请选择下载内容' } 'expanded episodes' | Out-Null
    Start-Sleep -Milliseconds 400
    if ((Tasks).Count -ne 1) { throw 'Expanded selection downloaded without confirmation.' }
    Invoke-Control 'Bangumi:202'
    $rename = Wait-Until { Find 'RenameFileName' } 'filename editor'
    $rename.GetCurrentPattern([Windows.Automation.ValuePattern]::Pattern).SetValue('自定义剧集😀')
    Invoke-Control '保存'
    Wait-Until { Find '自定义剧集😀.mp4' } 'filename preview' | Out-Null
    Invoke-Control '下载选中内容'
    Wait-Until { Tasks | Where-Object { $_.Item.EpisodeId -eq 202 -and $_.State -eq 4 } } 'linked episode 202' | Out-Null
    $named = Tasks | Where-Object { $_.Item.EpisodeId -eq 202 }
    if ($named.OutputFileName -ne '自定义剧集😀.mp4' -or $named.FinalFileName -ne '自定义剧集😀.mp4') { throw 'Custom filename was not persisted/published.' }
    Write-Output 'PASS per-item filename editor, preview and actual MP4 output'
    Write-Output 'PASS expanded episodes require confirmation and preserve the linked episode selection'
    Set-Options $true $false
    Set-Content -LiteralPath (Join-Path $testRoot 'mode.txt') -Value restricted
    Minimize-ToTray; $id = Copy-Link 'https://www.bilibili.com/video/av999'
    Forward 'notification' $id; Wait-Until { (Find '登录 B 站').Current.IsOffscreen -eq $false } 'guest 412 login guidance' | Out-Null
    Invoke-Control '登录 B 站'; Invoke-Control '关闭'
    if (-not (Find '重新解析')) { throw 'Login cancel lost retry action.' }
    Write-Output 'PASS guest 412 and login cancellation retain the retry page'
    Set-Content -LiteralPath (Join-Path $testRoot 'mode.txt') -Value ok
    Minimize-ToTray; $id = Copy-Link 'https://www.bilibili.com/cheese/play/ep201'
    Forward 'open'; Close-Window; Invoke-Control '退出应用'
    if (-not $qa.WaitForExit(8000)) { throw 'Explicit exit did not stop the process.' }
    $schemeHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($appDirectory + '\').ToUpperInvariant()))).Substring(0,12).ToLowerInvariant()
    Start-Process -FilePath ('bilidownload-qa-' + $schemeHash + '://clipboard/' + ([Guid]$id).ToString('N')) -WindowStyle Hidden | Out-Null
    $script:qa = Wait-Until { Get-Process -Name BiliDownload.QA -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1 } 'notification protocol cold-start window'
    Wait-Until { Tasks | Where-Object { $_.Item.Kind -eq 2 -and $_.Item.EpisodeId -eq 201 -and $_.State -eq 4 } } 'persisted activation after restart' | Out-Null
    $cold = Tasks | Where-Object { $_.Item.Kind -eq 2 -and $_.Item.EpisodeId -eq 201 }
    if ($cold.OutputFileName -notmatch '-模板-1.mp4$' -or $cold.FinalFileName -ne $cold.OutputFileName) { throw 'Template was not applied to cold-start automatic download.' }
    Write-Output 'PASS settings template persists and drives automatic download after restart'
    Write-Output 'PASS notification protocol cold start and persisted snapshot after process exit'
    $other = Start-Process -FilePath $exe -WindowStyle Hidden -PassThru
    if (-not $other.WaitForExit(8000)) { throw 'Secondary process did not exit.' }
    Write-Output 'PASS secondary launch forwards to the existing instance'
    $removed = Tasks | Where-Object { $_.Item.Kind -eq 2 -and $_.Item.EpisodeId -eq 201 }
    if (-not (Find '移除记录')) { Invoke-Control 'MoreButton' }
    Invoke-Control '移除记录'; Invoke-Control '移除'
    Wait-Until { -not (Tasks | Where-Object { $_.Id -eq $removed.Id }) } 'removed task' | Out-Null
    if (-not (Test-Path -LiteralPath $removed.FilePath)) { throw 'Removing a record deleted the video.' }
    Write-Output 'PASS completed record removal preserves video'
    Close-Window
    (Find 'RememberCloseChoice').GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Toggle()
    Invoke-Control '取消'
    Close-Window
    if ((Find 'RememberCloseChoice').GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Current.ToggleState -ne [Windows.Automation.ToggleState]::Off) { throw 'Cancel saved close choice.' }
    (Find 'RememberCloseChoice').GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Toggle()
    Invoke-Control '最小化到托盘'
    Wait-Until { -not [BackgroundNative]::IsWindowVisible($qa.MainWindowHandle) } 'remembered tray choice' | Out-Null
    Forward 'open'; Wait-Until { [BackgroundNative]::IsWindowVisible($qa.MainWindowHandle) } 'restored window' | Out-Null
    Close-Window
    Wait-Until { -not [BackgroundNative]::IsWindowVisible($qa.MainWindowHandle) } 'automatic tray close' | Out-Null
    Forward 'open'; Wait-Until { [BackgroundNative]::IsWindowVisible($qa.MainWindowHandle) } 'restored window' | Out-Null
    Set-Options $true $false
    Close-Window
    (Find 'RememberCloseChoice').GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Toggle()
    Invoke-Control '退出应用'; if (-not $qa.WaitForExit(8000)) { throw 'Final exit failed.' }
    Start-QA; Close-Window
    if (-not $qa.WaitForExit(8000)) { throw 'Remembered exit did not exit after restart.' }
    Write-Output 'PASS remembered exit survives restart'
    Write-Output 'PASS close choice cancellation, remember tray and settings reset to ask'
    Set-Content -LiteralPath (Join-Path $testRoot 'result.txt') -Value 'PASS offline Windows background integration, native notification registration, live COM callback and notification protocol cold start. Physical notification-center clicks and notification-disabled behavior need separate verification.'
    Write-Output "Results: $testRoot"
} catch {
    if ($qa -and -not $qa.HasExited) {
        (Root).FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition) |
            ForEach-Object { "$($_.Current.AutomationId): $($_.Current.Name)" } | Set-Content -LiteralPath (Join-Path $testRoot 'ui-error.txt')
    }
    throw
} finally {
    Get-Process -Name BiliDownload.QA -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
    $cleanup = Start-Process -FilePath $exe -ArgumentList '--uninstall-cleanup' -WindowStyle Hidden -PassThru
    if (-not $cleanup.WaitForExit(8000)) { $cleanup | Stop-Process -Force }
    $server | Stop-Process -Force -ErrorAction SilentlyContinue
    if ($lastCopied -and [Windows.Forms.Clipboard]::ContainsText() -and [Windows.Forms.Clipboard]::GetText() -eq $lastCopied) {
        if ($null -ne $originalText) { [Windows.Forms.Clipboard]::SetText($originalText) } else { [Windows.Forms.Clipboard]::Clear() }
    }
}
