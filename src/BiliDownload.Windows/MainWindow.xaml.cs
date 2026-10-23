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
    private string? pendingClipboard;
    private string? lastClipboard;
    private bool expectCommands;
    private string[] expectedLabels = [];
    public MainWindow()
    {
        InitializeComponent(); ExtendsContentIntoTitleBar = true; SetTitleBar(TitleRegion);
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        ApplyTheme();
        Root.SizeChanged += (_, e) => TitleSubtitle.Visibility = e.NewSize.Width >= 720 ? Visibility.Visible : Visibility.Collapsed;
        if (ContentFrame.Content == null) ContentFrame.Navigate(typeof(HomePage));
        tray = new(WinRT.Interop.WindowNative.GetWindowHandle(this), () => { AppWindow.Show(); Activate(); if (pendingClipboard != null) ViewClipboard_Click(this, new RoutedEventArgs()); }, () => { exiting = true; Close(); });
        AppWindow.Closing += (_, e) => { if (!exiting && App.Services.Settings.MonitorClipboard) { e.Cancel = true; AppWindow.Hide(); } };
        Activated += async (_, e) => { if (e.WindowActivationState != WindowActivationState.Deactivated && App.Services.Settings.DetectOnActivate) await CheckClipboardAsync(); };
        try { watcher = new(WinRT.Interop.WindowNative.GetWindowHandle(this), () => { if (App.Services.Settings.MonitorClipboard) DispatcherQueue.TryEnqueue(async () => await CheckClipboardAsync()); }); }
        catch (InvalidOperationException) { ClipboardNotice.Message = "系统暂时无法监听剪贴板，仍可手动粘贴链接。"; }
        Closed += async (_, _) => { watcher?.Dispose(); tray?.Dispose(); await App.Services.DisposeAsync(); };
    }
    public void ApplyTheme() => Root.RequestedTheme = App.Services.Settings.Theme switch { "Light" => ElementTheme.Light, "Dark" => ElementTheme.Dark, _ => ElementTheme.Default };
    public void ExitApplication() { exiting = true; Close(); }
    public void ConfigureSmoke(int width, string page, string state)
    {
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
        try
        {
            var content = Clipboard.GetContent();
            if (!content.Contains(StandardDataFormats.Text)) return;
            var text = await content.GetTextAsync();
            if (text == lastClipboard || LinkParser.Extract(text).Count == 0) return;
            lastClipboard = text; pendingClipboard = text; ClipboardNotice.IsOpen = true;
            // Flash the taskbar without taking focus away from the user's current app.
            if (App.Services.Settings.MonitorClipboard) { FlashWindow(WinRT.Interop.WindowNative.GetWindowHandle(this), true); tray?.NotifyLink(); }
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.COMException or InvalidOperationException) { }
    }
    [System.Runtime.InteropServices.DllImport("user32.dll")] private static extern bool FlashWindow(nint window, bool invert);
    private void ViewClipboard_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(HomePage), pendingClipboard); pendingClipboard = null; ClipboardNotice.IsOpen = false; AppWindow.Show(); Activate();
    }
}
