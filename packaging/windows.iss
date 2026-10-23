#ifndef PackageDir
  #error PackageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef FullFfmpegDir
  #error FullFfmpegDir is required
#endif
#ifndef AppVersion
  #define AppVersion "0.1.4"
#endif
#ifndef DirectoryHelper
  #error DirectoryHelper is required
#endif
#ifndef ApplicationId
  #define ApplicationId "BiliDownload.WkJ01N"
#endif
[Setup]
AppId={#ApplicationId}
AppName=BiliDownload
AppVersion={#AppVersion}
AppPublisher=WkJ01N
AppPublisherURL=https://github.com/WkJ01N/BiliDownload
AppSupportURL=https://github.com/WkJ01N/BiliDownload/issues
DefaultDirName={code:DefaultInstallDirectory}
DefaultGroupName=BiliDownload
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallFilesDir={app}\app\uninstall
UninstallDisplayIcon={app}\BiliDownload.exe
OutputDir={#OutputDir}
OutputBaseFilename=BiliDownload-{#AppVersion}-windows-x64-setup
SetupIconFile={#PackageDir}\app\Assets\AppIcon.ico
LicenseFile={#PackageDir}\docs\LICENSE
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
WizardSizePercent=130,120
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[LangOptions]
chinesesimp.DialogFontName=Microsoft YaHei UI
chinesesimp.DialogFontSize=9
[CustomMessages]
chinesesimp.TypeRecommended=推荐：精简版 FFmpeg
english.TypeRecommended=Recommended: minimal FFmpeg
chinesesimp.TypeFull=完整版 FFmpeg
english.TypeFull=Full FFmpeg
chinesesimp.TypeCore=仅主程序
english.TypeCore=Application only
chinesesimp.TypeCustom=自定义
english.TypeCustom=Custom
chinesesimp.Core=主程序与运行环境（必需）
english.Core=Application and runtime (required)
chinesesimp.Tools=音视频合并工具
english.Tools=Media merging tools
chinesesimp.Minimal=精简版（推荐）
english.Minimal=Minimal (recommended)
chinesesimp.Full=完整版
english.Full=Full
chinesesimp.None=暂不安装
english.None=Do not install
chinesesimp.ComponentHelp=精简版满足视频下载与合并需要；完整版提供更多通用媒体功能。不安装时，可稍后在应用设置中下载组件。
english.ComponentHelp=Minimal FFmpeg supports downloading and merging. Full FFmpeg provides additional media features. You can also install FFmpeg later in application settings.
chinesesimp.DirectoryHelp=仅为当前用户安装。优先使用 D 盘；目录需要权限时，仅安装辅助程序申请管理员权限，应用以普通权限运行。
english.DirectoryHelp=Installed for the current user only. Drive D is preferred. A helper requests permission when needed; the application runs without elevation.
chinesesimp.Fallback=无法使用默认 D 盘目录，已改用系统 Program Files。你仍可选择其他安装目录。
english.Fallback=The default directory on drive D is unavailable. System Program Files is selected instead. You may choose another folder.
chinesesimp.DirectoryError=无法准备安装目录。若取消了管理员授权，可返回选择可写目录后重试。
english.DirectoryError=Unable to prepare the install folder. If permission was canceled, go back and choose a writable folder.
chinesesimp.DesktopIcon=创建桌面快捷方式
english.DesktopIcon=Create a desktop shortcut
chinesesimp.Launch=启动 BiliDownload
english.Launch=Launch BiliDownload
[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; Flags: unchecked
[Types]
Name: "recommended"; Description: "{cm:TypeRecommended}"
Name: "full"; Description: "{cm:TypeFull}"
Name: "core"; Description: "{cm:TypeCore}"
Name: "custom"; Description: "{cm:TypeCustom}"; Flags: iscustom
[Components]
Name: "core"; Description: "{cm:Core}"; Types: recommended full core custom; Flags: fixed
Name: "ffmpeg"; Description: "{cm:Tools}"; Types: recommended full core custom; Flags: fixed
Name: "ffmpeg\minimal"; Description: "{cm:Minimal}"; Types: recommended; Flags: exclusive
Name: "ffmpeg\full"; Description: "{cm:Full}"; Types: full; Flags: exclusive
Name: "ffmpeg\none"; Description: "{cm:None}"; Types: core; Flags: exclusive
[InstallDelete]
#include "windows-obsolete-0.1.1.iss"
Type: files; Name: "{app}\app\tools\ffmpeg.exe"
Type: files; Name: "{app}\docs\ffmpeg\FFmpeg-source.tar.gz"
Type: files; Name: "{app}\docs\ffmpeg\LICENSE.txt"
Type: files; Name: "{app}\docs\ffmpeg\BUILD-CONFIGURATION.txt"
Type: files; Name: "{app}\docs\ffmpeg\SOURCE-INFORMATION.txt"
Type: files; Name: "{app}\docs\ffmpeg\complete.json"
Type: files; Name: "{app}\docs\ffmpeg\ffmpeg.json"
[Files]
Source: "{#DirectoryHelper}"; Flags: dontcopy
Source: "{#PackageDir}\*"; DestDir: "{app}"; Excludes: "app\tools\*,docs\ffmpeg\*"; Components: core; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#PackageDir}\app\tools\*"; DestDir: "{app}\app\tools"; Components: ffmpeg\minimal; Flags: ignoreversion
Source: "{#PackageDir}\docs\ffmpeg\*"; DestDir: "{app}\docs\ffmpeg"; Components: ffmpeg\minimal; Flags: ignoreversion recursesubdirs
Source: "{#FullFfmpegDir}\ffmpeg.exe"; DestDir: "{app}\app\tools"; Components: ffmpeg\full; Flags: ignoreversion
Source: "{#FullFfmpegDir}\*"; DestDir: "{app}\docs\ffmpeg"; Excludes: "ffmpeg.exe,ready.sha256"; Components: ffmpeg\full; Flags: ignoreversion recursesubdirs
[Icons]
#ifndef TestMode
Name: "{group}\BiliDownload"; Filename: "{app}\BiliDownload.exe"
Name: "{autodesktop}\BiliDownload"; Filename: "{app}\BiliDownload.exe"; Tasks: desktopicon
#endif
[Run]
Filename: "{app}\BiliDownload.exe"; Description: "{cm:Launch}"; Flags: nowait postinstall skipifsilent runasoriginaluser
#ifndef TestMode
[UninstallRun]
Filename: "{app}\app\BiliDownload.exe"; Parameters: "--uninstall-cleanup"; Flags: runhidden waituntilterminated skipifdoesntexist
#endif
[Code]
type
  TMonitorRect = record Left, Top, Right, Bottom: Integer; end;
  TMonitorData = record Size: Cardinal; Monitor, Work: TMonitorRect; Flags: Cardinal; end;
var
  DirectoryNotice, ComponentHelp: TNewStaticText;
  UsingFallback, HadPreviousInstall: Boolean;

function GetCurrentProcessId: Cardinal;
  external 'GetCurrentProcessId@kernel32.dll stdcall';
function MonitorFromWindow(Window: HWND; Flags: Cardinal): THandle;
  external 'MonitorFromWindow@user32.dll stdcall';
function GetMonitorInfo(Monitor: THandle; var Data: TMonitorData): Boolean;
  external 'GetMonitorInfoW@user32.dll stdcall';

function DefaultInstallDirectory(Param: String): String;
begin
  UsingFallback := not DirExists('D:\');
  if UsingFallback then Result := ExpandConstant('{commonpf64}\BiliDownload')
  else Result := 'D:\Program Files\BiliDownload';
end;

procedure InitializeWizard;
var Monitor: TMonitorData; AvailableWidth, AvailableHeight: Integer;
begin
  HadPreviousInstall := RegKeyExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{#ApplicationId}_is1');
  { Reserve a real glyph gutter: custom themed checklists otherwise draw at the clipping edge. }
  WizardForm.ComponentsList.Offset := ScaleX(12);
  WizardForm.ComponentsList.MinItemHeight := ScaleY(24);
  WizardForm.TasksList.Offset := ScaleX(12);
  WizardForm.TasksList.MinItemHeight := ScaleY(24);
  WizardForm.RunList.Offset := ScaleX(12);
  WizardForm.RunList.MinItemHeight := ScaleY(24);
  WizardForm.LicenseAcceptedRadio.Left := WizardForm.LicenseAcceptedRadio.Left + ScaleX(8);
  WizardForm.LicenseAcceptedRadio.Width := WizardForm.LicenseAcceptedRadio.Width - ScaleX(16);
  WizardForm.LicenseNotAcceptedRadio.Left := WizardForm.LicenseNotAcceptedRadio.Left + ScaleX(8);
  WizardForm.LicenseNotAcceptedRadio.Width := WizardForm.LicenseNotAcceptedRadio.Width - ScaleX(16);
  ComponentHelp := TNewStaticText.Create(WizardForm);
  ComponentHelp.Parent := WizardForm.SelectComponentsPage;
  WizardForm.ComponentsList.Height := WizardForm.ComponentsList.Height - ScaleY(56);
  ComponentHelp.Left := WizardForm.ComponentsList.Left + ScaleX(8);
  ComponentHelp.Top := WizardForm.ComponentsList.Top + WizardForm.ComponentsList.Height + ScaleY(4);
  ComponentHelp.Width := WizardForm.ComponentsList.Width - ScaleX(16);
  ComponentHelp.Height := ScaleY(52);
  ComponentHelp.AutoSize := False;
  ComponentHelp.WordWrap := True;
  ComponentHelp.Caption := CustomMessage('ComponentHelp');
  ComponentHelp.Anchors := [akLeft, akRight, akBottom];
  DirectoryNotice := TNewStaticText.Create(WizardForm);
  DirectoryNotice.Parent := WizardForm.SelectDirPage;
  DirectoryNotice.Left := WizardForm.DirEdit.Left;
  DirectoryNotice.Top := WizardForm.DirEdit.Top + WizardForm.DirEdit.Height + ScaleY(12);
  DirectoryNotice.Width := WizardForm.SelectDirPage.ClientWidth - DirectoryNotice.Left;
  DirectoryNotice.Height := ScaleY(100);
  DirectoryNotice.AutoSize := False;
  DirectoryNotice.WordWrap := True;
  DirectoryNotice.Caption := CustomMessage('DirectoryHelp');
  DirectoryNotice.Anchors := [akLeft, akTop, akRight];
  Monitor.Size := SizeOf(Monitor);
  if GetMonitorInfo(MonitorFromWindow(WizardForm.Handle, 2), Monitor) then begin
    AvailableWidth := Monitor.Work.Right - Monitor.Work.Left - ScaleX(16);
    AvailableHeight := Monitor.Work.Bottom - Monitor.Work.Top - ScaleY(16);
    if WizardForm.Width > AvailableWidth then WizardForm.Width := AvailableWidth;
    if WizardForm.Height > AvailableHeight then WizardForm.Height := AvailableHeight;
    WizardForm.Left := Monitor.Work.Left + (Monitor.Work.Right - Monitor.Work.Left - WizardForm.Width) div 2;
    WizardForm.Top := Monitor.Work.Top + (Monitor.Work.Bottom - Monitor.Work.Top - WizardForm.Height) div 2;
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpSelectDir then begin
    DirectoryNotice.Caption := CustomMessage('DirectoryHelp');
    if UsingFallback and (CompareText(WizardDirValue, ExpandConstant('{commonpf64}\BiliDownload')) = 0) then
      DirectoryNotice.Caption := CustomMessage('Fallback') + #13#10 + DirectoryNotice.Caption;
  end;
end;

function DirectoryWritable(const Directory: String): Boolean;
var Probe: String;
begin
  Result := ForceDirectories(Directory);
  if Result then begin
    Probe := AddBackslash(Directory) + '.bili-write-test-' + IntToStr(GetCurrentProcessId);
    if FileExists(Probe) then begin Result := False; exit; end;
    Result := SaveStringToFile(Probe, 'test', False);
    if Result then DeleteFile(Probe);
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer; Target: String;
begin
  Result := ''; Target := WizardDirValue;
  if DirectoryWritable(Target) then exit;
  ExtractTemporaryFile('PrepareDirectory.exe');
  if ShellExec('runas', ExpandConstant('{tmp}\PrepareDirectory.exe'),
      '--prepare "' + Target + '" ' + IntToStr(GetCurrentProcessId), '', SW_HIDE, ewWaitUntilTerminated, Code) then begin
    if (Code = 0) and DirectoryWritable(Target) then exit;
  end;
  if not HadPreviousInstall and (CompareText(Target, 'D:\Program Files\BiliDownload') = 0) and (Code <> 1223) then begin
    WizardForm.DirEdit.Text := ExpandConstant('{commonpf64}\BiliDownload');
    UsingFallback := True;
    Result := CustomMessage('Fallback') + #13#10 + CustomMessage('DirectoryError');
  end else Result := CustomMessage('DirectoryError');
end;
