using BiliDownload.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace BiliDownload_Windows.Pages;

public sealed partial class SettingsPage : Page
{
    private CancellationTokenSource? componentDownload;
    public SettingsPage()
    {
        InitializeComponent(); var settings = App.Services.Settings;
        NamingVariables.ItemsSource = FileNaming.Variables.Select(v => "{" + v + "}"); NamingTemplate.Text = settings.FileNameTemplate;
        DirectoryBox.Text = settings.DownloadDirectory; FfmpegBox.Text = settings.FfmpegPath;
        DetectSwitch.IsOn = settings.DetectOnActivate; MonitorSwitch.IsOn = settings.MonitorClipboard; ThemeBox.SelectedValue = settings.Theme;
        AutoDownloadSwitch.IsOn = settings.AutoDownloadOnNotificationClick; ExpandRelatedSwitch.IsOn = settings.ExpandRelatedItems;
        App.Services.Notifications.RefreshStatus(); NotificationStatus.Text = App.Services.Notifications.Status;
        ComponentChoice.ItemsSource = App.Services.ComponentPackages; ComponentChoice.SelectedIndex = 0; RefreshComponents();
        Unloaded += (_, _) => componentDownload?.Cancel();
        Loaded += async (_, _) => await RefreshAccountAsync();
    }
    private void RefreshComponents()
    {
        var resolved = App.Services.ResolveFfmpeg();
        ComponentStatus.Text = Path.IsPathFullyQualified(resolved) && File.Exists(resolved) ? "当前工具：" + resolved : "当前配置：" + resolved + "（将从系统 PATH 查找）";
        var package = ComponentChoice.SelectedItem as ComponentPackage;
        RemoveComponent.IsEnabled = componentDownload == null && package != null && App.Services.Components.InstalledPath(package) != null;
    }
    private void ComponentChoice_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (ComponentChoice.SelectedItem is not ComponentPackage package) return;
        ComponentSize.Text = $"下载约 {package.DownloadBytes / 1048576.0:F1} MiB · 安装约 {package.InstalledBytes / 1048576.0:F1} MiB（含许可及源码） · {package.Version}";
        RefreshComponents();
    }
    private bool QueueBusy() => App.Services.Queue.Snapshot().Any(t => t.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging);
    private void UseFfmpeg(string path)
    {
        App.Services.SaveSettings(App.Services.Settings with { FfmpegPath = path });
        FfmpegBox.Text = path; RefreshComponents();
    }
    private async void InstallComponent_Click(object sender, RoutedEventArgs e)
    {
        if (ComponentChoice.SelectedItem is not ComponentPackage package || componentDownload != null) return;
        componentDownload = new(); var token = componentDownload.Token;
        InstallComponent.IsEnabled = ComponentChoice.IsEnabled = RemoveComponent.IsEnabled = BundledComponent.IsEnabled = false;
        CancelComponent.IsEnabled = true; ComponentProgress.Visibility = Visibility.Visible; ComponentProgress.IsIndeterminate = true;
        try
        {
            var progress = new Progress<BiliDownload.Core.ComponentProgress>(p => {
                ComponentMessage.Text = p.Message;
                ComponentProgress.IsIndeterminate = p.Fraction == null;
                if (p.Fraction != null) ComponentProgress.Value = p.Fraction.Value * 100;
            });
            var path = await App.Services.Components.InstallAsync(package, progress, token);
            await MediaDownloader.CheckFfmpegAsync(path, token);
            UseFfmpeg(path); ComponentMessage.Text = "组件已安装并选用。可返回原任务点击重试。";
        }
        catch (OperationCanceledException) { ComponentMessage.Text = "下载已取消，原工具配置保留。"; }
        catch (Exception ex) { ComponentMessage.Text = ex is BiliException ? ex.Message : "组件下载或安装失败，请检查网络、磁盘空间后重试。"; }
        finally
        {
            componentDownload.Dispose(); componentDownload = null;
            InstallComponent.IsEnabled = ComponentChoice.IsEnabled = BundledComponent.IsEnabled = true;
            CancelComponent.IsEnabled = false; ComponentProgress.Visibility = Visibility.Collapsed; RefreshComponents();
        }
    }
    private void CancelComponent_Click(object sender, RoutedEventArgs e) => componentDownload?.Cancel();
    private void RemoveComponent_Click(object sender, RoutedEventArgs e)
    {
        if (ComponentChoice.SelectedItem is not ComponentPackage package) return;
        if (QueueBusy()) { ComponentMessage.Text = "请先暂停正在运行的下载，再移除组件。"; return; }
        try {
            var installed = App.Services.Components.InstalledPath(package);
            App.Services.Components.Remove(package);
            if (installed != null && App.Services.Settings.FfmpegPath == installed) UseFfmpeg("ffmpeg");
            ComponentMessage.Text = "下载的组件已移除，视频及任务记录保留。"; RefreshComponents();
        } catch (Exception) { ComponentMessage.Text = "组件正在使用或目录不可写，暂时无法移除。"; }
    }
    private void UseBundled_Click(object sender, RoutedEventArgs e)
    {
        var bundled = Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");
        UseFfmpeg(File.Exists(bundled) ? bundled : "ffmpeg"); ComponentMessage.Text = "工具配置已更新。";
    }
    private async Task RefreshAccountAsync()
    {
        try { var data = await App.Services.Bili.ApiAsync("https://api.bilibili.com/x/web-interface/nav"); AccountStatus.Text = data.Num("isLogin") != 0 ? $"已登录 · {data.Str("uname")}" : "未登录 · 可下载游客可访问的内容"; }
        catch (BiliException ex) { AccountStatus.Text = ex.Kind == BiliErrorKind.RequestRestricted ? "请求受限，暂时无法验证登录状态" : "未登录或登录已过期"; }
        catch (Exception) { AccountStatus.Text = "暂时无法验证登录状态，请检查网络"; }
    }
    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        ((Button)sender).IsEnabled = false;
        try
        {
            var result = await BiliDownload_Windows.Services.LoginFlow.ShowAsync(XamlRoot);
            if (result.Outcome == LoginOutcome.Success) await RefreshAccountAsync();
            else if (result.Outcome == LoginOutcome.Failed) Show(result.Message ?? "登录失败，请重试。", InfoBarSeverity.Warning);
        }
        finally { ((Button)sender).IsEnabled = true; }
    }
    private async void Directory_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var picker = new FolderPicker(); picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(App.Main));
            var folder = await picker.PickSingleFolderAsync(); if (folder != null) DirectoryBox.Text = folder.Path;
        }
        catch (Exception) { Show("无法打开目录选择器，也可以手动填写路径。", InfoBarSeverity.Warning); }
    }
    private async void Logout_Click(object sender, RoutedEventArgs e)
    {
        try { App.Services.Logout(); await RefreshAccountAsync(); Show("已删除本机登录信息。", InfoBarSeverity.Success); }
        catch (Exception) { Show("删除登录信息失败，请检查本地目录权限。", InfoBarSeverity.Error); }
    }
    private void Save_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!Path.IsPathFullyQualified(DirectoryBox.Text) || string.IsNullOrWhiteSpace(FfmpegBox.Text)) throw new BiliException("请填写绝对保存路径及 FFmpeg 程序路径。");
            FileNaming.Validate(NamingTemplate.Text);
            Directory.CreateDirectory(DirectoryBox.Text);
            App.Services.SaveSettings(App.Services.Settings with { DownloadDirectory = DirectoryBox.Text.Trim(), FfmpegPath = FfmpegBox.Text.Trim(),
                DetectOnActivate = DetectSwitch.IsOn, MonitorClipboard = MonitorSwitch.IsOn, Theme = ThemeBox.SelectedValue as string ?? "Default",
                AutoDownloadOnNotificationClick = AutoDownloadSwitch.IsOn, ExpandRelatedItems = ExpandRelatedSwitch.IsOn, FileNameTemplate = NamingTemplate.Text });
            App.Main.ApplyTheme(); Show("设置已保存，新任务将使用新的保存目录。", InfoBarSeverity.Success);
        }
        catch (Exception ex) { Show(ex is BiliException ? ex.Message : "设置保存失败，请检查目录是否可写。", InfoBarSeverity.Error); }
    }
    private void Naming_Changed(object sender, TextChangedEventArgs e) {
        if (NamingPreview == null) return;
        try { NamingPreview.Text = "预览：" + FileNaming.Render(new("video:123", "示例视频", "第二部分", "", MediaKind.Video, 1, "BV1xx411c7mD", 123, 0, 2, "", 3), NamingTemplate.Text); }
        catch (BiliException ex) { NamingPreview.Text = ex.Message; }
    }
    private void InsertVariable_Click(object sender, RoutedEventArgs e) {
        var start = NamingTemplate.SelectionStart;
        NamingTemplate.Text = NamingTemplate.Text.Insert(start, (string)((Button)sender).Content);
    }
    private void ResetNaming_Click(object sender, RoutedEventArgs e) => NamingTemplate.Text = FileNaming.DefaultTemplate;
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
