# BiliDownload

B 站视频下载与快捷分享工具，免费开源，完全在本地运行。Windows 使用 C# / .NET 10 / WinUI 3 官方导航模板；Android 使用 Kotlin / Jetpack Compose。

## 当前版本：0.1.2 开发预览

- 导入完整视频链接、b23.tv 短链接、BV/av 号和分享文案；多链接及多 P、剧集、课程条目选择。
- 普通投稿、番剧影视、课程的独立解析适配器；已发布为普通投稿的直播回放走投稿下载链路。
- 下载前确认，默认最高 1080p；优先 H.264 + AAC，输出 MP4。画质列表以第一个条目为参考，各项实际下载不超过所选上限。
- 两个并行任务、暂停继续、失败重试、地址刷新、本地 SQLite 历史记录。重启后中断任务显示为暂停，等待手动继续。
- Windows 扫码登录、DPAPI 加密会话；Android 网页登录、Keystore 加密会话。
- Windows 文件复制、打开目录、调用播放器、可选剪贴板监听与托盘；Android 分享导入、前台识别、快捷入口、后台下载与系统分享。

这是开发预览版，尚未达到公开稳定版验收状态。当前开发环境真实 B 站接口探测返回 HTTP 412，因此普通投稿到番剧、课程的在线下载与登录尚未完成真实账号验证。Android 已通过构建、单元测试和 Lint 错误检查，本机没有连接设备或可用模拟器，后台生命周期、系统媒体合并以及微信/QQ 分享仍需设备实测。Windows 已完成启动及界面渲染检查、本地 HTTP 下载与 FFmpeg 合并检查。独立直播回放链接尚未适配；直播录制、音频单独导出、字幕、弹幕、压缩、收藏夹及 UP 主批量下载不在本预览版内。


## 0.1.2 改进

- 按参考设计重绘粉色 B 与下载箭头图标，统一 Windows EXE、托盘、标题栏及 Android 自适应/主题图标。可编辑源文件为 [branding/icon.svg](branding/icon.svg)，Windows 可运行 `scripts/build-brand.ps1` 重新导出各尺寸。
- Windows 改用所需 WinUI 组件包，移除未使用的 AI/ML/Widgets 依赖，保留启动必需运行时。
- 安装器提供“精简版 FFmpeg / 完整版 / 不安装”三种选择，默认精简版；便携包默认包含精简版。精简版仅约 1.5 MiB，提供当前下载功能所需的无损合并。
- 设置页提供组件下载、SHA-256 校验、取消、切换及移除。下载组件保存在 `%LOCALAPPDATA%/BiliDownload/components`，切换及移除保留视频和任务记录。未安装 FFmpeg 时仍可解析链接，开始下载前会提示补装。
- 核心运行环境为必需组件，保持离线可启动。完整版组件下载包较大；安装器为离线选择同时包含两种 FFmpeg，所以安装器文件大小不等于默认安装占用。

## 0.1.1 改进

- Windows 根目录整理为启动器、app、docs；新增按当前用户安装程序，桌面快捷方式可选。
- 下载及完成页面按任务状态显示操作，窄窗口下自动收纳至“更多”菜单；导航栏自动收起，解析页控件适应窄窗口。
- Android 使用不同的原生矢量底栏图标，页面切换加入约 220ms 淡入淡出与轻微位移动画；保留各页滚动位置、解析结果及选择。
- 两端设置底部加入 [GitHub 仓库](https://github.com/WkJ01N/BiliDownload) 入口，删除应用内宣传文字。
- 412 和登录过期使用不同错误类别。游客受限时提供登录入口；登录成功保留原操作，由用户点击重新解析或重试。已登录仍受限时提示稍后重试，不自动退出或循环要求登录。
- 登录可能改善请求条件，但不保证解除所有请求限制。

完整验证范围及尚需真机检查的项目见 [VALIDATION.md](VALIDATION.md)。

## 运行

Windows 10 22H2 / Windows 11 x64：运行 `artifacts/BiliDownload-0.1.2-windows-x64-setup.exe` 安装，默认只安装到当前用户，无需管理员权限。也可解压便携包 `artifacts/BiliDownload-0.1.2-windows-x64-portable.zip` 后运行根目录 `BiliDownload.exe`。根目录仅含启动程序、`app/` 和 `docs/`；运行时、WinUI 资源和 FFmpeg 位于 `app/`，文档及依赖许可位于 `docs/`。粘贴链接、解析、勾选条目后下载；在“已完成”中复制文件，再粘贴到支持文件粘贴的聊天应用。复制文件不保证聊天应用会将它当作内嵌视频发送。

Android 10+：安装 `artifacts/android/BiliDownload-0.1.2-preview.apk`。这是使用开发调试密钥签名的预览包，正式公开发行前需要配置专用发布签名。可从 B 站分享给本应用，也可粘贴链接。下载默认保存至 `Movies/BiliDownload`，设置中可通过系统目录选择器改变位置。通过“分享视频”选择接收应用。

剪贴板识别默认关闭。Android 系统限制后台读取剪贴板，快捷方式和快捷设置磁贴会先打开本应用再读取。Windows 开启后台监听后，关闭窗口会收起到托盘；右键托盘图标退出。应用没有开机自启和遥测。

## 构建与验证

Windows 构建需要 PowerShell 7、.NET SDK 10.0.113 或同一功能带的新补丁、可访问 NuGet，以及 MSVC C++ 开发者命令行（或 MinGW-w64 的 gcc/windres）用于编译原生启动器；无需手动安装 WinUI 模板。构建脚本按清单中的 SHA-256 下载已发布的 LGPL 组件包和 Inno Setup 7.1.0 编译器，缓存在 `.build/`。

```powershell
dotnet test tests/BiliDownload.Tests/BiliDownload.Tests.csproj
./scripts/build-windows.ps1
# 可选：提供带许可和来源说明的 LGPL 工具目录，该目录内应直接包含 ffmpeg.exe
./scripts/build-windows.ps1 -FfmpegDirectory C:/path/to/lgpl-tools
```

发布目录 `artifacts/windows-0.1.2/` 包含 .NET 和 Windows App SDK 运行时。自包含部署不使用裁剪，避免破坏 XAML 绑定与 SQLite。安装器、便携 ZIP 使用同一目录结构；升级、卸载保留 `%LOCALAPPDATA%/BiliDownload` 中的设置与历史以及用户视频。`-SkipInstaller` 可仅构建便携包。`scripts/test-windows-ui.ps1` 验证窄窗口操作栏，`scripts/test-installer.ps1` 使用独立 QA 安装标识验证安装和卸载，不替换用户安装。

Android 需要 JDK 21、Android SDK Platform 36 / Build Tools 36.0.0。设置 `ANDROID_HOME`，或在 `android/local.properties` 配置本机 `sdk.dir`（该文件不提交）。

```powershell
./scripts/build-android.ps1 -JavaHome C:/path/to/jdk-21
```

Linux/macOS 构建 Android：`cd android` 后执行 `bash ./gradlew assembleDebug testDebugUnitTest lintDebug`。Gradle Wrapper 分发包使用 SHA-256 校验。仓库提供 GitHub Actions 构建与测试工作流，未自动发布 Release。

组件维护者可在 Git Bash/MSYS2、MinGW-w64 GCC、GNU Make 和 binutils 环境中运行 `scripts/prepare-components.ps1`，从固定 SHA-256 的 FFmpeg 源码编译精简版并准备两个组件 ZIP。此命令会更新 `Assets/components.json` 的校验值；发布时必须将生成的原始 ZIP 上传到清单指定的 Release 地址，普通构建不重新压缩组件包。`packaging/ffmpeg-minimal.json` 和 `scripts/build-ffmpeg.sh` 记录精简版来源及全部构建参数；精简版不包含转码、网络下载及额外解码功能，这些操作由应用完成或不属于当前版本功能。

真实接口只读探测：

```powershell
dotnet run --project tools/BiliDownload.Probe -- https://www.bilibili.com/video/BV1xx411c7mD
```

下载与合并链路可用本地生成的 `video.mp4`（H.264、无音频）和 `audio.m4a`（AAC）验证：

```powershell
dotnet run --project tools/BiliDownload.Probe -- --verify-local C:/path/to/fixtures C:/path/to/ffmpeg.exe
```

该命令只在本机临时监听 HTTP，生成的文件保存在样本目录 `output/`；不要把真实账号凭据放进测试样本。

## 后续验收

需要在用户本机网络与测试账号下验证：游客和登录画质、扫码与网页登录、番剧/已购课程完整访问、短链接跳转、长视频断点恢复、下载取消与磁盘不足、Android 10/13/14+ 后台行为、MP4 音画同步、微信和 QQ 文件接收。受保护的视频源或仅能获取试看内容时，会明确失败。

项目代码采用 MIT；依赖及媒体工具说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
