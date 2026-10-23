# 0.1.1 验证记录

验证日期：2026-10-23。本文件记录本地验证范围，GitHub Actions 结果见仓库 Actions 页面。

## 已通过

- .NET 核心测试：38 项，包含链接识别、接口适配、HTTP/API 错误分类、游客 412、会话失效、已登录受限、登录结果提示、重试再次受限、旧记录兼容及 FFmpeg 路径回退。
- Windows Release 自包含构建，原生 Win32 启动器编译成功。发布根目录只有 `BiliDownload.exe`、`app/`、`docs/`；WinUI PRI、运行时及 FFmpeg 位于对应子目录。
- Windows 在本机 150% 显示缩放下进行 13 组窗口渲染检查：480、600、900、1180 DIP，下载任务的全部七种状态，以及额外的 360 DIP 完成/失败任务和设置页面。检查显示的操作与任务状态一致、按钮边界、溢出菜单实际展开和菜单内的任务绑定。可用截图和报告保存在本机 `.build/ui-validation/`，不上传源码仓库。
- 独立 QA 安装标识下验证当前用户安装、重复安装升级路径、中文及空格路径、任意工作目录和启动器参数转发；卸载程序位于 `app/uninstall/`。卸载后应用文件移除，隔离的设置/历史样本及安装目录中用户自行放入的视频仍保留。未覆盖用户现有安装或修改真实下载记录。
- 固定 SHA-256 下载 LGPL FFmpeg，发布脚本拒绝 GPL/nonfree 配置，保存许可、构建配置和匹配源码。用包内 FFmpeg 验证本地 HTTP 下载、暂停继续、SQLite 持久化、合并和最终保存；产物包含 H.264 视频与 AAC 音频轨道。
- Android Debug APK 构建、12 项 JVM 单元测试和 Lint 通过。Lint 为 0 errors、22 warnings，主要涉及依赖可更新提示、KTX 建议、同步会话保存及应用级 Context 的静态持有。
- 核对两端 GitHub 入口均指向 `https://github.com/WkJ01N/BiliDownload`。会话、构建缓存、产物、本机 SDK 路径和签名密钥均由 `.gitignore` 排除。

## 仍需运行验证

- 本机没有连接 Android 设备或可用模拟器。矢量图标效果、快速切换、系统关闭动画、滚动与解析选择保留、登录往返、后台生命周期、MediaMuxer 音画同步及微信/QQ 分享需要设备实测。页面状态放在动画容器外，Compose 原生动画使用系统动画时长缩放。
- Windows 100%、125%、200% 显示缩放及多显示器切换没有实际切换验证；默认浏览器链接、系统播放器和聊天应用文件粘贴仍需人工交互检查。
- 扫码登录、Android 网页登录及登录取消返回的真实交互，游客与登录账号画质、番剧及已购课程完整下载需要用户网络和测试账号。当前环境此前的真实接口探测返回 HTTP 412；错误分类和恢复提示已通过模拟响应测试，登录不保证解除请求限制。
- 安装升级验证使用同一 0.1.1 包重复安装；后续版本发布前仍应运行跨版本升级检查。卸载保留用户数据的策略是安装脚本只移除它安装的文件，不删除 AppData、视频或用户自行添加的文件。
- APK 使用调试签名；Windows 安装器和程序未配置代码签名。正式分发前需安排专用签名与真机验收。

## 复现命令

```powershell
dotnet test tests/BiliDownload.Tests/BiliDownload.Tests.csproj
./scripts/build-windows.ps1
./scripts/test-windows-ui.ps1
./scripts/test-installer.ps1
./scripts/build-android.ps1 -JavaHome C:/path/to/jdk-21
dotnet run --project tools/BiliDownload.Probe -- --verify-local C:/path/to/fixtures artifacts/windows-0.1.1/app/tools/ffmpeg.exe
```

UI 与安装检查使用隔离数据目录；真实账号凭据不应加入测试样本。
