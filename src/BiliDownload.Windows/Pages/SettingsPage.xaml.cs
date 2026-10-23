using BiliDownload.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace BiliDownload_Windows.Pages;

public sealed partial class SettingsPage : Page
{
    public SettingsPage()
    {
        InitializeComponent(); var settings = App.Services.Settings;
        DirectoryBox.Text = settings.DownloadDirectory; FfmpegBox.Text = settings.FfmpegPath;
        DetectSwitch.IsOn = settings.DetectOnActivate; MonitorSwitch.IsOn = settings.MonitorClipboard; ThemeBox.SelectedValue = settings.Theme;
        Loaded += async (_, _) => await RefreshAccountAsync();
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
            Directory.CreateDirectory(DirectoryBox.Text);
            App.Services.SaveSettings(new(DirectoryBox.Text.Trim(), FfmpegBox.Text.Trim(), DetectSwitch.IsOn, MonitorSwitch.IsOn, ThemeBox.SelectedValue as string ?? "Default"));
            App.Main.ApplyTheme(); Show("设置已保存，新任务将使用新的保存目录。", InfoBarSeverity.Success);
        }
        catch (Exception ex) { Show(ex is BiliException ? ex.Message : "设置保存失败，请检查目录是否可写。", InfoBarSeverity.Error); }
    }
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
