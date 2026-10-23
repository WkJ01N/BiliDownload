#ifndef PackageDir
  #error PackageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef AppVersion
  #define AppVersion "0.1.1"
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
Compression=lzma2/fast
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
[Files]
Source: "{#PackageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
#ifndef TestMode
Name: "{group}\BiliDownload"; Filename: "{app}\BiliDownload.exe"
Name: "{autodesktop}\BiliDownload"; Filename: "{app}\BiliDownload.exe"; Tasks: desktopicon
#endif
[Run]
Filename: "{app}\BiliDownload.exe"; Description: "启动 BiliDownload"; Flags: nowait postinstall skipifsilent
