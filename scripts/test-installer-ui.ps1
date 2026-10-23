param([string]$Installer)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes,System.Drawing
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class InstallerNative {
 public delegate bool EnumProc(IntPtr hwnd, IntPtr param);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr param);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hwnd,StringBuilder name,int max);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out Rect rect);
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
}
'@
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ('.build/installer-ui/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $testRoot | Out-Null
if (-not $Installer) {
    $compiler = & (Join-Path $PSScriptRoot 'install-inno.ps1')
    $full = & (Join-Path $PSScriptRoot 'get-component.ps1') -Id ffmpeg-full
    & $compiler /Q "/DPackageDir=$(Join-Path $projectRoot 'artifacts/windows-0.1.3')" "/DFullFfmpegDir=$full" "/DDirectoryHelper=$(Join-Path $projectRoot '.build/installer-helper/PrepareDirectory.exe')" "/DOutputDir=$testRoot" "/DApplicationId=BiliDownload.WkJ01N.UI.$([Guid]::NewGuid().ToString('N'))" /DTestMode=1 (Join-Path $projectRoot 'packaging/windows.iss')
    if ($LASTEXITCODE -ne 0) { throw 'QA installer compilation failed.' }
    $Installer = Join-Path $testRoot 'BiliDownload-0.1.3-windows-x64-setup.exe'
}
function Find-Wizard {
    $script:wizard = [IntPtr]::Zero
    [InstallerNative]::EnumWindows([InstallerNative+EnumProc]{ param($window,$unused)
        $name = [Text.StringBuilder]::new(100)
        [InstallerNative]::GetClassName($window,$name,100) | Out-Null
        if ($name.ToString() -eq 'TWizardForm' -and [InstallerNative]::IsWindowVisible($window)) {
            [uint32]$processId=0; [InstallerNative]::GetWindowThreadProcessId($window,[ref]$processId) | Out-Null
            $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($process -and $process.StartTime -ge $script:started) { $script:wizard=$window; $script:wizardProcessId=$processId; return $false }
        }
        return $true
    },[IntPtr]::Zero) | Out-Null
    return $script:wizard
}
function Controls { @($script:root.FindAll([Windows.Automation.TreeScope]::Descendants,[Windows.Automation.Condition]::TrueCondition)) | Where-Object { -not $_.Current.IsOffscreen } }
function Invoke-Element($Element) {
    $pattern = $null
    if ($Element.TryGetCurrentPattern([Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)) { $pattern.Invoke() }
    elseif ($Element.TryGetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)) { $pattern.Select() }
    elseif ($Element.Current.NativeWindowHandle -ne 0) { [InstallerNative]::PostMessage([IntPtr]$Element.Current.NativeWindowHandle,0xF5,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null }
    else { throw "Cannot invoke $($Element.Current.Name)" }
    Start-Sleep -Milliseconds 250
}
function Screenshot([string]$Path) {
    $rect = [InstallerNative+Rect]::new(); [InstallerNative]::GetWindowRect($wizard,[ref]$rect) | Out-Null
    $bitmap = [Drawing.Bitmap]::new($rect.Right-$rect.Left,$rect.Bottom-$rect.Top)
    $graphics = [Drawing.Graphics]::FromImage($bitmap); $dc = $graphics.GetHdc()
    try { [InstallerNative]::PrintWindow($wizard,$dc,2) | Out-Null }
    finally { $graphics.ReleaseHdc($dc); $graphics.Dispose() }
    $bitmap.Save($Path,[Drawing.Imaging.ImageFormat]::Png); $bitmap.Dispose()
}
foreach ($language in @('chinesesimp','english')) {
    $script:started=Get-Date; $script:wizardProcessId=0
    $bootstrap = Start-Process -FilePath $Installer -ArgumentList @('/SP-',"/LANG=$language",'/NORESTART') -WindowStyle Hidden -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do { $wizard=Find-Wizard; if ($wizard -ne [IntPtr]::Zero) { break }; Start-Sleep -Milliseconds 150 } while ([DateTime]::UtcNow -lt $deadline)
        if ($wizard -eq [IntPtr]::Zero) { throw 'Installer wizard did not open.' }
        $script:root = [Windows.Automation.AutomationElement]::FromHandle($wizard)
        for ($page=0; $page -lt 8; $page++) {
            Start-Sleep -Milliseconds 250
            $controls = Controls
            $bounds = $root.Current.BoundingRectangle
            foreach ($control in $controls) {
                $b=$control.Current.BoundingRectangle
                if (-not $b.IsEmpty -and ($b.Left -lt $bounds.Left-1 -or $b.Right -gt $bounds.Right+1)) { throw "Clipped control: $($control.Current.Name)" }
            }
            Screenshot (Join-Path $testRoot "$language-$page.png")
            $controls | ForEach-Object { "$($_.Current.ControlType.ProgrammaticName): $($_.Current.Name)" } | Set-Content -LiteralPath (Join-Path $testRoot "$language-$page.txt")
            $install = $controls | Where-Object { $_.Current.ClassName -match 'Button' -and $_.Current.Name -match '^(安装|Install)' } | Select-Object -First 1
            if ($install) { break }
            $accept = $controls | Where-Object { $_.Current.Name -match '^(我同意|我接受|I accept)' } | Select-Object -First 1
            if ($accept) { Invoke-Element $accept }
            $next = Controls | Where-Object { $_.Current.Name -match '^(下一步|Next)' } | Select-Object -First 1
            if (-not $next -or -not $next.Current.IsEnabled) { throw 'Cannot advance installer page.' }
            Invoke-Element $next
        }
        Write-Output "PASS $language wizard pages and control bounds; DPI=$([InstallerNative]::GetDpiForWindow($wizard))"
        $cancel = Controls | Where-Object { $_.Current.Name -match '^(取消|Cancel)' } | Select-Object -First 1
        if ($cancel) { Invoke-Element $cancel }
        Start-Sleep -Milliseconds 300
        # The confirmation dialog is outside TWizardForm; end only this test's setup process tree below.
    } finally {
        if ($script:wizardProcessId) { Stop-Process -Id $script:wizardProcessId -Force -ErrorAction SilentlyContinue }
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($bootstrap.Id)" -ErrorAction SilentlyContinue
        foreach ($child in $children) { Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue }
        $bootstrap | Stop-Process -Force -ErrorAction SilentlyContinue
    }
}
Write-Output "Installer screenshots: $testRoot"
