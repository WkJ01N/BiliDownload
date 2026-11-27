# BiliDownload

跨平台媒体下载工具，支持 **B 站**、**YouTube / YouTube Music** 与 **X（Twitter）**。Windows 端基于 C# / .NET 10 / WinUI 3，Android 端基于 Kotlin / Jetpack Compose，无遥测、无开机自启。

## 核心功能

- **多平台解析**：支持 B 站投稿、多 P、番剧、课程与 `b23.tv` 短链；YouTube 视频、Shorts、直播回放（`youtube.com/live/...`）、播放列表与 YouTube Music；X 帖子图文、视频及动图（可选 MP4 / 循环 GIF，正文保存为 Markdown）。
- **视频与音频下载**：优先提供兼容的 H.264 + AAC（MP4）组合，支持最高画质上限选择；仅音频模式下支持原音导出（M4A / Ogg Opus / FLAC）及 MP3 转码，并自动写入音乐标签与封面。
- **内置账号登录**：B 站、YouTube、X 均支持应用内直接网页登录（本地加密存储），同时保留 `cookies.txt` 导入作为备选。
- **队列与文件管理**：下载队列与已完成列表分离；已完成支持多选批量分享/复制/删除，移除任务时可勾选同步删除本地文件；支持自定义文件名模板、变量插入与三档列表显示密度。

## v0.4.3 主要变化

- **双端交互与动效完善**：补齐三选一分段器滑块平移动效、顶部提示垂直居中与倒计时自动收起进度条，以及列表增删与展开过渡。
- **YouTube / X / B 站登录体验**：双端补齐内置网页登录并支持 SPA 自动轮询感知，B 站检测到登录后自动验证保存并返回；`cookies.txt` 收纳至次级菜单。
- **Android 动效、模糊与设置重构**：底部悬浮导航栏与批量栏接入实时毛玻璃模糊，配合液态物理交互、无闪烁切页动画与分级设置子页面。
- **性能与体积优化**：YouTube 引擎新增常驻预热 Worker 与持久化缓存加快解析速度，优化启动期卡顿，并开启 Android Release R8 与 ABI 分包（`arm64-v8a` 约 18.6 MB）。

## 运行说明

- **Windows 10 22H2 / Windows 11 x64**：支持安装器与便携包，数据保存在 `%LOCALAPPDATA%/BiliDownload`。首次使用 YouTube / X 或完整音频转码时，可在设置中一键下载校验对应扩展组件。
- **Android 10+**：主流机型优先安装 `arm64-v8a` Release APK；视频默认保存至 `Movies/BiliDownload`，音频默认保存至 `Music/BiliDownload`。首次解析 YouTube / X 时按提示在应用内下载约 2.93 MiB 的解析组件即可使用。

## 构建与测试

```powershell
# 核心与 Python 引擎测试
dotnet test tests/BiliDownload.Tests/BiliDownload.Tests.csproj
python -B -m unittest discover -s tests/youtube

# 构建 Windows 安装器与便携包（需 .NET 10 SDK、PowerShell 7 与 MSVC）
./scripts/build-windows.ps1
```

```bash
# Android 测试、Lint 与 Release 分包（需 JDK 21、SDK 36 与已编译的 audio jniLibs）
cd android
./gradlew testDebugUnitTest lintDebug assembleRelease
```

## 协议与参考

- 项目源码采用 **MIT** 许可协议。
- 第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，详细测试范围见 [VALIDATION.md](VALIDATION.md)。
