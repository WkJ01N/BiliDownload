using BiliDownload.Core;
using Microsoft.Windows.AppNotifications;
using System.Xml.Linq;

namespace BiliDownload_Windows.Services;

public sealed class NotificationService : IDisposable
{
    private bool registered;
    public bool IsRegistered => registered;
    public string Status { get; private set; } = "系统通知尚未初始化";
    public void Register(Action<Guid> activate)
    {
        try
        {
            if (!AppNotificationManager.IsSupported()) { Status = "系统通知不支持管理员方式运行，请以普通权限启动应用。"; return; }
            NotificationProtocol.Register();
            AppNotificationManager.Default.NotificationInvoked += (_, e) => {
                if (e.Arguments.TryGetValue("request", out var value) && Guid.TryParse(value, out var id)) activate(id);
            };
            AppNotificationManager.Default.Register();
            registered = true; RefreshStatus();
        }
        catch (Exception ex) {
            if (App.IsAutomationTest) File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "qa-notification-error.txt"), ex.ToString());
            Status = "系统通知注册失败，请以普通权限启动应用；仍可从托盘打开并粘贴链接。";
        }
    }
    public void RefreshStatus()
    {
        if (!registered) return;
        try { Status = AppNotificationManager.Default.Setting == AppNotificationSetting.Enabled ? "系统通知可用" :
            "Windows 已关闭此应用的通知，请在系统通知设置中启用。"; }
        catch (Exception) { Status = "暂时无法读取系统通知状态。"; }
    }
    public void Show(ClipboardRequest request)
    {
        if (!registered) return;
        try
        {
            // Protocol activation restores the app even after exit; the unguessable ID resolves only a local, unconsumed snapshot.
            var payload = new XElement("toast", new XAttribute("activationType", "protocol"),
                new XAttribute("launch", ClipboardActivation.Create(NotificationProtocol.Scheme, request.Id)),
                new XElement("visual", new XElement("binding", new XAttribute("template", "ToastGeneric"),
                    new XElement("text", "检测到 B 站链接"), new XElement("text", "点击打开 BiliDownload，按你的设置下载或选择内容。"))));
            var notification = new AppNotification(payload.ToString());
            notification.Tag = request.Id.ToString("N"); notification.Group = "clipboard";
            notification.Expiration = request.CreatedAt.AddHours(24);
            AppNotificationManager.Default.Show(notification); RefreshStatus();
        }
        catch (Exception) { Status = "系统通知发送失败，可从托盘打开应用查看链接。"; }
    }
    public static async Task UninstallAsync()
    {
        NotificationProtocol.Unregister();
        try {
            AppNotificationManager.Default.Register();
            await AppNotificationManager.Default.RemoveAllAsync();
            AppNotificationManager.Default.UnregisterAll();
        } catch (Exception) { /* Uninstall must remain available when notifications are disabled. */ }
    }
    public void Dispose() { if (registered) { try { AppNotificationManager.Default.Unregister(); } catch (Exception) { } registered = false; } }
}
