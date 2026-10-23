# Windows 0.1.3 验证记录

日期：2026-10-23。Android 保持 0.1.2，本轮未修改 Android 源码；原版本的设备验证限制继续适用。

## 本轮已通过

- .NET 核心测试 58 项：链接与接口适配、游客和登录状态下的 412／会话失效恢复、旧记录兼容、组件校验和路径回退；新增目标 P／集、短链接跳转与 412 分类、多链接目标保留、通知唤起协议输入校验、通知快照独立性、过期及一次消费、设置一次性迁移、自动下载与展开选择规则。
- Windows Release 自包含构建、原生启动器及目录辅助程序编译成功。根目录仅有 `BiliDownload.exe`、`app/`、`docs/`；FFmpeg、运行时、PRI 和图标资源均在子目录。原有组件包继续使用 v0.1.2 的固定地址及 SHA-256。
- 本机 150% 缩放下的 13 组应用 UI 检查：480、600、900、1180 DIP，以及 360 DIP 完成／失败页；覆盖全部任务状态、按钮边界、溢出菜单及任务绑定。
- 隔离安装器的中文、英文许可页、目录页、组件页、附加任务页和准备安装页均截图并检查边界。已人工查看组件页和许可页，左侧选项未裁切；新安装页面显示 `D:\Program Files\BiliDownload`。
- 原生目录辅助程序验证：新目录创建、原安装用户所有权、已有非空目录拒绝、共享父目录／盘符根目录拒绝、重解析点拒绝。程序不授予父目录权限；真实跨账号提权另列为待测。
- 独立 QA 安装 ID 验证 0.1.2 → 0.1.3 升级保留原目录，精简／完整／不安装 FFmpeg 模式切换、同版本重装、中文空格路径、任意工作目录、启动器参数及卸载。卸载保留隔离的设置／历史样本及用户自行加入的视频。
- 原有本地 HTTP 链路验证：下载、暂停继续、SQLite 持久化、H.264 视频与 AAC 音频合并及最终保存成功。
- 离线后台集成验证使用独立 QA 程序、私有数据目录和本地模拟接口，未使用真实账号。已通过关闭取消、最小化到托盘、链接检测、指定 P2 自动下载与真实 FFmpeg 合并、重复激活防重放。
- 已通过关闭自动下载后仅解析、展开剧集后先确认且保留指定集、游客 412 的登录入口与取消返回、通知快照跨进程退出保存、重启后的激活处理和第二进程转发退出。
- 补充通过原生通知注册与发送、实际 COM 运行中回调，以及 Windows 专用协议冷启动：应用退出后从系统协议重新启动并下载未消费快照。通知正文使用专用协议唤起；实际通知中心鼠标点击仍另列为待测。补齐 Foundation 通知 API 必需的约 34 KiB 官方 Insights 资源，未引入 AI/ML 运行库。

## 尚需实测

- Windows 100%、125%、200% 和多显示器切换；安装进度及完成页面的人工交互。本机实际缩放为 150%，未修改用户系统缩放。
- 无 D 盘机器的实际自动回退，以及受保护 Program Files 中真实 UAC 同意、取消、换用另一管理员账号的完整安装／升级／卸载；已验证辅助程序自身的用户归属与目录边界。
- Windows 通知中心实际鼠标点击、勿扰／通知关闭的系统行为、资源管理器重启、注销和安装器 Restart Manager 关闭。离线测试已覆盖应用侧通知激活路由和状态恢复。
- 真实 B 站扫码登录、游客及登录账号画质、番剧与已购课程。此前真实接口返回 HTTP 412，模拟恢复测试不代表真实账号已经验收。
- Windows 未配置代码签名。Android 仍为 0.1.2 调试签名包，本机无 Android 设备或可用模拟器；真机后台、媒体合并和聊天应用分享尚需验证。

## 复现

```powershell
dotnet test tests/BiliDownload.Tests/BiliDownload.Tests.csproj
./scripts/build-windows.ps1
./scripts/test-install-directory.ps1
./scripts/test-windows-ui.ps1
./scripts/test-installer.ps1
pwsh -NoProfile -STA -File scripts/test-installer-ui.ps1
pwsh -NoProfile -STA -File scripts/test-windows-background.ps1
dotnet run --project tools/BiliDownload.Probe -- --verify-local C:/path/to/fixtures artifacts/windows-0.1.3/app/tools/ffmpeg.exe
```

UI、安装器截图和后台报告位于本机 `.build/`，不上传源码仓库。后台测试会短暂替换文本剪贴板，在其内容未被用户再次修改时恢复原文本；建议在空闲桌面运行。GitHub Actions 检查核心测试、两端构建以及发布结构和原生目录助手边界。
