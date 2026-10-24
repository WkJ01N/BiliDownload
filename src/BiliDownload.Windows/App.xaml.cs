using BiliDownload_Windows.Services;
using Microsoft.UI.Xaml;

namespace BiliDownload_Windows;

public partial class App : Application
{
    public static AppServices Services { get; private set; } = null!;
    public static MainWindow Main { get; private set; } = null!;
    private static SingleInstance? instance;
    private NotificationService? secondaryNotifications;
    private Microsoft.UI.Dispatching.DispatcherQueue? dispatcher;
    public static bool IsAutomationTest => string.Equals(Path.GetFileName(Environment.ProcessPath), "BiliDownload.QA.exe", StringComparison.OrdinalIgnoreCase);
    public static void ReleaseInstance() { instance?.Dispose(); instance = null; }
    public App()
    {
        UnhandledException += (_, e) => WriteSmokeFailure(e.Exception);
        try { InitializeComponent(); } catch (Exception ex) { WriteSmokeFailure(ex); throw; }
    }
    private static void WriteSmokeFailure(Exception exception)
    {
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Length >= 3 && arguments[1] == "--smoke-test") File.WriteAllText(arguments[2], exception.ToString());
        if (IsAutomationTest) File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "qa-startup-error.txt"), exception.ToString());
    }
    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs();
        var smoke = arguments.Length >= 3 && arguments[1] == "--smoke-test";
        dispatcher = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        var dataDirectory = IsAutomationTest ? Path.Combine(AppContext.BaseDirectory, "qa-data") : smoke ? Path.Combine(Path.GetDirectoryName(Path.GetFullPath(arguments[2]))!, "smoke-data") :
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BiliDownload");
        instance = new(dataDirectory);
        var protocolActivation = arguments.Length == 2 && BiliDownload.Core.ClipboardActivation.TryParse(arguments[1], NotificationProtocol.Scheme, out _);
        var notificationId = protocolActivation ? Guid.Parse(new Uri(arguments[1]).AbsolutePath.TrimStart('/')) : Guid.Empty;
        if (arguments.Length == 2 && arguments[1] == "--uninstall-cleanup")
        {
            if (!instance.IsPrimary) {
                try { await instance.ForwardAsync(new("exit")); } catch (Exception) { ReleaseInstance(); Exit(); return; }
                ReleaseInstance();
                for (var attempt = 0; attempt < 80; attempt++) {
                    instance = new(dataDirectory); if (instance.IsPrimary) break;
                    ReleaseInstance(); await Task.Delay(100);
                }
            }
            if (instance?.IsPrimary == true) await NotificationService.UninstallAsync();
            ReleaseInstance(); Exit(); return;
        }
        var comActivation = arguments.Any(a => a.StartsWith("----AppNotificationActivated:", StringComparison.Ordinal));
        if (!instance.IsPrimary)
        {
            if (comActivation)
            {
                secondaryNotifications = new();
                secondaryNotifications.Register(async id => {
                    try { await instance.ForwardAsync(new("notification", id)); }
                    catch (Exception) { }
                    DispatcherQueueExit();
                });
                if (!secondaryNotifications.IsRegistered) { DispatcherQueueExit(); return; }
                var expiry = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
                expiry.Tick += (_, _) => { expiry.Stop(); DispatcherQueueExit(); }; expiry.Start();
            }
            else
            {
                try { await instance.ForwardAsync(protocolActivation ? new("notification", notificationId) : new("open")); }
                catch (Exception) { MessageBox(0, "BiliDownload 正在启动或退出，请稍后再试。", "BiliDownload", 0x40); }
                ReleaseInstance(); Exit();
            }
            return;
        }
        try {
            Services = new(dataDirectory); Main = new();
            instance.Listen(request => Main.DispatcherQueue.TryEnqueue(() => Main.EnqueueActivation(request)));
            var receivedNotification = false;
            if (!smoke) Services.Notifications.Register(id => Main.DispatcherQueue.TryEnqueue(() => {
                receivedNotification = true; Main.EnqueueActivation(new("notification", id));
            }));
            if (!comActivation || smoke || !Services.Notifications.IsRegistered) Main.Activate();
            else {
                var expiry = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
                expiry.Tick += (_, _) => {
                    expiry.Stop();
                    if (!receivedNotification && !Main.IsExiting) { Main.RestoreWindow(); Main.ShowMessage("系统未能传递此通知的链接，请重新复制或手动粘贴。"); }
                }; expiry.Start();
            }
            if (protocolActivation) Main.EnqueueActivation(new("notification", notificationId));
        }
        catch (Exception ex) { WriteSmokeFailure(ex); throw; }
        if (smoke)
        {
            var path = arguments[2];
            var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
            timer.Tick += async (_, _) => {
                timer.Stop();
                try {
                    if (arguments.Length >= 4 && int.TryParse(arguments[3], out var width)) Main.ConfigureSmoke(width, arguments.ElementAtOrDefault(4) ?? "home", arguments.ElementAtOrDefault(5) ?? "Completed", arguments.ElementAtOrDefault(6) ?? "Default");
                    await Task.Delay(500);
                    var detail = await Main.VerifyCommandsAsync(); await Main.CaptureAsync(Path.ChangeExtension(path, ".png")); File.WriteAllText(path, "WinUI window initialized successfully\n" + detail);
                }
                catch (Exception ex) { File.WriteAllText(path, "UI capture failed: " + ex.GetType().Name); }
                Main.ExitApplication();
            };
            timer.Start();
        }
    }
    private void DispatcherQueueExit() => dispatcher?.TryEnqueue(() => {
        secondaryNotifications?.Dispose(); ReleaseInstance(); Exit();
    });
    [System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint = "MessageBoxW", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern int MessageBox(nint window, string text, string caption, uint type);
}
