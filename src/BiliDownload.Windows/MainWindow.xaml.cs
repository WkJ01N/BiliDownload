using BiliDownload.Core;
using BiliDownload_Windows.Pages;
using BiliDownload_Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;

namespace BiliDownload_Windows;

public sealed partial class MainWindow : Window
{
    private ClipboardWatcher? watcher;
    private TrayIcon? tray;
    private bool exiting;
    private bool allowClose, closePrompt, systemClosing, foreground;
    private Guid? pendingClipboard;
    private string? lastClipboard;
    private int clipboardRevision;
    private SystemLifetime? lifetime;
    private readonly Queue<ActivationRequest> activations = new();
    private bool processingActivation;
    public bool IsExiting => exiting;
    private bool expectCommands;
    private string[] expectedLabels = [];
    public MainWindow()
    {
        InitializeComponent(); ExtendsContentIntoTitleBar = true; SetTitleBar(TitleRegion);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        ApplyTheme();
        Root.ActualThemeChanged += (_, _) => ApplyTitleBarColors();
        Navigation.DisplayModeChanged += (_, _) => AdjustNavigationLayout();
        Root.Loaded += (_, _) => AdjustNavigationLayout();
        if (ContentFrame.Content == null) ContentFrame.Navigate(typeof(HomePage));
        tray = new(WinRT.Interop.WindowNative.GetWindowHandle(this), RestoreWindow, () => _ = RequestExitAsync());
        lifetime = new(WinRT.Interop.WindowNative.GetWindowHandle(this), () => systemClosing = true,
            ending => { systemClosing = ending; if (ending) _ = RequestExitAsync(); });
        AppWindow.Closing += async (_, e) => {
            if (allowClose) return;
            e.Cancel = true;
            if (systemClosing) { await RequestExitAsync(); return; }
            if (exiting || closePrompt) return;
            closePrompt = true;
            try {
                var preference = App.Services.Settings.CloseBehavior;
                if (preference == "Exit") { await RequestExitAsync(); return; }
                if (preference == "Tray" && tray?.IsAvailable == true) { AppWindow.Hide(); return; }
                var remember = new CheckBox { Content = "记住我的选择" };
                Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(remember, "RememberCloseChoice");
                var panel = new StackPanel { Spacing = 12 };
                panel.Children.Add(new TextBlock { Text = "最小化到托盘后，下载会继续运行。退出应用将暂停未完成任务，下次可继续。", TextWrapping = TextWrapping.Wrap });
                panel.Children.Add(remember);
                var dialog = new ContentDialog { XamlRoot = Root.XamlRoot, Title = "关闭 BiliDownload",
                    Content = panel,
                    PrimaryButtonText = "最小化到托盘", SecondaryButtonText = "退出应用", CloseButtonText = "取消",
                    DefaultButton = ContentDialogButton.Primary };
                var result = await DialogCoordinator.ShowAsync(dialog);
                if (remember.IsChecked == true && result != ContentDialogResult.None) {
                    try { App.Services.SaveSettings(App.Services.Settings with { CloseBehavior = result == ContentDialogResult.Primary ? "Tray" : "Exit" }); }
                    catch { ShowMessage("关闭偏好保存失败，下次仍会询问。"); }
                    if (ContentFrame.Content is SettingsPage settingsPage) settingsPage.SyncClosePreference();
                }
                if (result == ContentDialogResult.Primary) {
                    if (tray?.IsAvailable == true) AppWindow.Hide();
                    else ShowMessage("托盘图标暂时不可用，窗口已保留。可以稍后重试或选择退出应用。");
                } else if (result == ContentDialogResult.Secondary) await RequestExitAsync();
            } finally { closePrompt = false; }
        };
        Activated += async (_, e) => {
            foreground = e.WindowActivationState != WindowActivationState.Deactivated;
            if (foreground && App.Services.Settings.DetectOnActivate) await CheckClipboardAsync();
        };
        try { watcher = new(WinRT.Interop.WindowNative.GetWindowHandle(this), () => { if (App.Services.Settings.MonitorClipboard) DispatcherQueue.TryEnqueue(async () => await CheckClipboardAsync()); }); }
        catch (InvalidOperationException) { ShowMessage("系统暂时无法监听剪贴板，仍可手动粘贴链接。"); }
        Closed += (_, _) => { watcher?.Dispose(); tray?.Dispose(); lifetime?.Dispose(); };
        BehaviorNotice.IsOpen = App.Services.ClipboardMigrationNotice;
    }
    private void AdjustNavigationLayout() => ContentFrame.Margin = new Thickness(0, Navigation.DisplayMode == NavigationViewDisplayMode.Minimal ? 40 : 0, 0, 0);
    public void ApplyTheme() {
        Root.RequestedTheme = App.Services.Settings.Theme switch { "Light" => ElementTheme.Light, "Dark" => ElementTheme.Dark, _ => ElementTheme.Default };
        ApplyTitleBarColors();
    }
    private void ApplyTitleBarColors() {
        var dictionary = Application.Current.Resources.MergedDictionaries.First(d => d.Source?.OriginalString.EndsWith("RadixTheme.xaml") == true);
        var theme = (ResourceDictionary)dictionary.ThemeDictionaries[Root.ActualTheme == ElementTheme.Light ? "Light" : "Default"];
        Windows.UI.Color Color(string key) => ((Microsoft.UI.Xaml.Media.SolidColorBrush)theme[key]).Color;
        var bar = AppWindow.TitleBar;
        bar.ButtonForegroundColor = Color("TextFillColorPrimaryBrush");
        bar.ButtonInactiveForegroundColor = Color("TextFillColorSecondaryBrush");
        bar.ButtonBackgroundColor = bar.ButtonInactiveBackgroundColor = Color("ApplicationPageBackgroundThemeBrush");
        bar.ButtonHoverBackgroundColor = Color("ControlFillColorSecondaryBrush");
        bar.ButtonPressedBackgroundColor = Color("ControlFillColorTertiaryBrush");
        bar.ButtonHoverForegroundColor = bar.ButtonPressedForegroundColor = Color("TextFillColorPrimaryBrush");
    }
    public void ExitApplication() => _ = RequestExitAsync();
    public async Task RequestExitAsync()
    {
        if (exiting) return; exiting = true;
        (ContentFrame.Content as HomePage)?.CancelPending();
        watcher?.Dispose(); watcher = null;
        try { await App.Services.DisposeAsync(); }
        finally { tray?.Dispose(); tray = null; lifetime?.Dispose(); lifetime = null; App.ReleaseInstance(); allowClose = true; Close(); }
    }
    public void RestoreWindow()
    {
        AppWindow.Show();
        (AppWindow.Presenter as Microsoft.UI.Windowing.OverlappedPresenter)?.Restore();
        Activate(); SetForegroundWindow(WinRT.Interop.WindowNative.GetWindowHandle(this));
    }
    public void OpenSettings() { Navigation.SelectedItem = Navigation.SettingsItem; ContentFrame.Navigate(typeof(SettingsPage)); }
    public void OpenQueue()
    {
        Navigation.SelectedItem = Navigation.MenuItems.OfType<NavigationViewItem>().First(i => i.Tag as string == "queue");
        ContentFrame.Navigate(typeof(QueuePage), "queue");
    }
    public void EnqueueActivation(ActivationRequest request)
    {
        if (exiting) return;
        if (request.Action == "exit") { _ = RequestExitAsync(); return; }
        activations.Enqueue(request); if (!processingActivation) _ = ProcessActivationsAsync();
    }
    private async Task ProcessActivationsAsync()
    {
        processingActivation = true;
        try {
            while (activations.TryDequeue(out var activation) && !exiting) {
                if (activation.Action == "exit") { await RequestExitAsync(); break; }
                await DialogCoordinator.WaitForIdleAsync();
                if (exiting) break;
                RestoreWindow();
                if (activation.Action == "open") continue;
                try {
                    var request = activation.Id is { } id ? App.Services.ClipboardRequests.Consume(id) : null;
                    if (request == null) { ShowMessage("此链接提示已处理或已超过 24 小时，请重新复制链接。"); continue; }
                    ClipboardNotice.IsOpen = false; pendingClipboard = null;
                    Navigation.SelectedItem = Navigation.MenuItems.OfType<NavigationViewItem>().First(i => i.Tag as string == "home");
                    if (ContentFrame.Content is not HomePage) ContentFrame.Navigate(typeof(HomePage));
                    await ((HomePage)ContentFrame.Content).OpenClipboardAsync(request, activation.Action == "notification");
                } catch (Exception) { ShowMessage("无法处理此通知，请重新复制链接，或手动粘贴后解析。"); }
            }
        } finally { processingActivation = false; }
    }
    public void ShowMessage(string message)
    {
        ClipboardNotice.Title = "提示"; ClipboardNotice.Message = message; ClipboardNotice.IsOpen = true;
        ViewClipboardButton.IsEnabled = pendingClipboard.HasValue;
    }
    private void DisableClipboard_Click(object sender, RoutedEventArgs e)
    {
        App.Services.SaveSettings(App.Services.Settings with { MonitorClipboard = false }); BehaviorNotice.IsOpen = false;
    }
    public void ConfigureSmoke(int width, string page, string state, string theme = "Default")
    {
        Root.RequestedTheme = theme switch { "Light" => ElementTheme.Light, "Dark" => ElementTheme.Dark, _ => ElementTheme.Default };
        var scale = Root.XamlRoot?.RasterizationScale ?? 1.5;
        AppWindow.Resize(new Windows.Graphics.SizeInt32((int)(width * scale), (int)(850 * scale)));
        if (page is "queue" or "completed")
        {
            expectCommands = true;
            expectedLabels = state switch {
                "Completed" => ["复制文件", "文件夹", "播放"],
                "Failed" => ["登录 B 站", "继续 / 重试", "取消"],
                "Paused" => ["继续 / 重试", "取消"],
                "Canceled" => [],
                _ => ["暂停", "取消"]
            };
            expectedLabels = expectedLabels.Append("移除记录").ToArray();
            Navigation.SelectedItem = Navigation.MenuItems.OfType<NavigationViewItem>().First(i => i.Tag as string == page);
            ContentFrame.Navigate(typeof(QueuePage), page);
            ((QueuePage)ContentFrame.Content).ShowSmokeState(Enum.Parse<BiliDownload.Core.DownloadState>(state));
        }
        else if (page == "settings") { Navigation.SelectedItem = Navigation.SettingsItem; ContentFrame.Navigate(typeof(SettingsPage)); }
    }
    public async Task<string> VerifyCommandsAsync()
    {
        static IEnumerable<DependencyObject> Descendants(DependencyObject root)
        {
            for (var i = 0; i < Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(root); i++)
            {
                var child = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(root, i); yield return child;
                foreach (var descendant in Descendants(child)) yield return descendant;
            }
        }
        var details = new List<string> { $"Width={Root.ActualWidth}; DpiScale={Root.XamlRoot?.RasterizationScale}" };
        var bars = Descendants(Root).OfType<CommandBar>().ToArray();
        if (expectCommands && bars.Length == 0) throw new InvalidOperationException("Expected task commands were not rendered.");
        foreach (var bar in bars.Where(b => b.PrimaryCommands.OfType<AppBarButton>().Any(c => c.IsInOverflow && c.Visibility == Visibility.Visible))) bar.IsOpen = true;
        await Task.Delay(150);
        var visible = bars.SelectMany(b => b.PrimaryCommands.OfType<AppBarButton>()).Where(b => b.Visibility == Visibility.Visible).ToArray();
        if (expectCommands && !expectedLabels.Order().SequenceEqual(visible.Select(b => b.Label).Order())) throw new InvalidOperationException("Task command visibility does not match its state.");
        if (visible.Any(b => b.Tag is not BiliDownload_Windows.ViewModels.DownloadTaskViewModel)) throw new InvalidOperationException("Command lost its task binding.");
        foreach (var bar in bars)
            foreach (var button in bar.PrimaryCommands.OfType<AppBarButton>().Where(b => b.Visibility == Visibility.Visible))
            {
                if (!button.IsInOverflow && button.ActualWidth > 0)
                {
                    var position = button.TransformToVisual(bar).TransformPoint(new Windows.Foundation.Point(0, 0));
                    if (position.X < -1 || position.X + button.ActualWidth > bar.ActualWidth + 1) throw new InvalidOperationException("Clipped command: " + button.Label);
                }
                if (button.IsInOverflow && button.ActualWidth <= 0) throw new InvalidOperationException("Overflow command was not rendered: " + button.Label);
                details.Add($"{button.Label}: overflow={button.IsInOverflow}");
            }
        return string.Join("\n", details);
    }
    public async Task CaptureAsync(string path)
    {
        var bitmap = new Microsoft.UI.Xaml.Media.Imaging.RenderTargetBitmap();
        await bitmap.RenderAsync(Root);
        var buffer = await bitmap.GetPixelsAsync();
        using var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
        var encoder = await Windows.Graphics.Imaging.BitmapEncoder.CreateAsync(Windows.Graphics.Imaging.BitmapEncoder.PngEncoderId, stream);
        var bytes = System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions.ToArray(buffer);
        encoder.SetPixelData(Windows.Graphics.Imaging.BitmapPixelFormat.Bgra8, Windows.Graphics.Imaging.BitmapAlphaMode.Premultiplied,
            (uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight, 96, 96, bytes);
        await encoder.FlushAsync();
        using var input = System.IO.WindowsRuntimeStreamExtensions.AsStreamForRead(stream);
        input.Position = 0; using var file = File.Create(path); await input.CopyToAsync(file);
    }
    private void Navigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (ContentFrame == null) return;
        if (args.IsSettingsSelected) ContentFrame.Navigate(typeof(SettingsPage));
        else if (args.SelectedItem is NavigationViewItem item)
            ContentFrame.Navigate(item.Tag as string == "home" ? typeof(HomePage) : typeof(QueuePage), item.Tag as string);
    }
    public async Task CheckClipboardAsync()
    {
        var revision = ++clipboardRevision;
        try
        {
            await Task.Delay(120);
            if (revision != clipboardRevision || exiting) return;
            var content = Clipboard.GetContent();
            if (!content.Contains(StandardDataFormats.Text)) { lastClipboard = null; return; }
            var text = await content.GetTextAsync();
            if (revision != clipboardRevision || exiting) return;
            var links = ClipboardPolicy.Links(text); var signature = string.Join('\n', links);
            if (signature == lastClipboard) return;
            lastClipboard = signature; if (links.Length == 0) return;
            var request = App.Services.ClipboardRequests.Create(links); pendingClipboard = request.Id;
            ClipboardNotice.Title = "发现 B 站链接"; ClipboardNotice.Message = "";
            ViewClipboardButton.IsEnabled = true; ClipboardNotice.IsOpen = true;
            if (!foreground && App.Services.Settings.MonitorClipboard) App.Services.Notifications.Show(request);
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.COMException or InvalidOperationException or IOException or UnauthorizedAccessException) { }
    }
    [System.Runtime.InteropServices.DllImport("user32.dll")] private static extern bool SetForegroundWindow(nint window);
    private void ViewClipboard_Click(object sender, RoutedEventArgs e)
    {
        if (pendingClipboard is { } id) EnqueueActivation(new("preview", id));
    }
}
