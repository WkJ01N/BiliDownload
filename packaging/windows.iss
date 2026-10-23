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
  #define AppVersion "0.1.2"
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
DefaultDirName={localappdata}\Programs\BiliDownload
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
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: unchecked
[Types]
Name: "recommended"; Description: "推荐安装：主程序 + 精简版 FFmpeg"
Name: "full"; Description: "完整安装：主程序 + 完整版 FFmpeg"
Name: "core"; Description: "仅主程序：使用已有 FFmpeg，或稍后在设置中下载"
Name: "custom"; Description: "自定义"; Flags: iscustom
[Components]
Name: "core"; Description: "主程序与必要的 .NET / WinUI 运行环境"; Types: recommended full core custom; Flags: fixed
Name: "ffmpeg"; Description: "FFmpeg 音视频合并工具（可稍后在设置中安装）"; Types: recommended full core custom; Flags: fixed
Name: "ffmpeg\minimal"; Description: "精简版：下载所需的合并功能（推荐）"; Types: recommended; Flags: exclusive
Name: "ffmpeg\full"; Description: "完整版：通用编解码与媒体工具"; Types: full; Flags: exclusive
Name: "ffmpeg\none"; Description: "不安装 FFmpeg"; Types: core; Flags: exclusive
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
Filename: "{app}\BiliDownload.exe"; Description: "启动 BiliDownload"; Flags: nowait postinstall skipifsilent
