using BiliDownload.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using BiliDownload_Windows.Services;

namespace BiliDownload_Windows.Pages;

public sealed partial class HomePage : Page
{
    private CancellationTokenSource? parsing;
    private string? lastInput;
    public HomePage() { InitializeComponent(); NavigationCacheMode = NavigationCacheMode.Enabled; }
    protected override void OnNavigatedTo(NavigationEventArgs e) { if (e.Parameter is string text && text != "home") LinkInput.Text = text; }
    protected override void OnNavigatedFrom(NavigationEventArgs e) { parsing?.Cancel(); }
    private async void Parse_Click(object sender, RoutedEventArgs e)
    {
        parsing?.Cancel(); parsing = new(); var ct = parsing.Token;
        var requestInput = LinkInput.Text;
        var previousIds = lastInput == requestInput ? Results.SelectedItems.Cast<MediaItem>().Select(i => i.Id).ToHashSet() : null;
        var previousQuality = lastInput == requestInput ? (QualityBox.SelectedItem as MediaVariant)?.Quality : null;
        Busy.IsActive = true; ParseButton.IsEnabled = false; DownloadButton.IsEnabled = false; Notice.IsOpen = false;
        LoginButton.Visibility = Visibility.Collapsed; RetryButton.Visibility = Visibility.Collapsed; RetryButton.IsEnabled = false;
        if (lastInput != requestInput) { Results.ItemsSource = null; QualityBox.ItemsSource = null; }
        try
        {
            var catalogs = await App.Services.Bili.ResolveAsync(requestInput, ct);
            var items = catalogs.SelectMany(c => c.Items).ToArray();
            Results.ItemsSource = items; ResultTitle.Text = $"找到 {items.Length} 个条目 · 请选择下载内容";
            lastInput = requestInput;
            // Default to the first item; a season must be explicitly selected by the user.
            if (previousIds != null) { foreach (var item in items.Where(i => previousIds.Contains(i.Id))) Results.SelectedItems.Add(item); }
            else if (items.Length > 0) Results.SelectedItem = items[0];
            var variants = await App.Services.Bili.GetVariantsAsync(items[0], ct);
            if (variants.Count == 0) throw new BiliException("当前没有可用的兼容清晰度。");
            QualityBox.ItemsSource = variants;
            QualityBox.SelectedItem = variants.FirstOrDefault(v => v.Quality == previousQuality) ?? variants.Where(v => v.Quality <= 80).MaxBy(v => v.Quality) ?? variants.MinBy(v => v.Quality);
            DownloadButton.IsEnabled = true;
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) {
            Show(ex is BiliException ? ex.Message : "解析失败，请检查网络后重试。", InfoBarSeverity.Error);
            LoginButton.Visibility = AuthRecovery.NeedsLogin((ex as BiliException)?.Kind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
            RetryButton.Visibility = Visibility.Visible;
        }
        finally { Busy.IsActive = false; ParseButton.IsEnabled = true; RetryButton.IsEnabled = true; }
    }
    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        LoginButton.IsEnabled = false;
        var result = await LoginFlow.ShowAsync(XamlRoot);
        LoginButton.IsEnabled = true;
        if (AuthRecovery.LoginMessage(result) is { } message) Show(message, result.Outcome == LoginOutcome.Success ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
        if (result.Outcome == LoginOutcome.Success) { LoginButton.Visibility = Visibility.Collapsed; RetryButton.Visibility = Visibility.Visible; }
    }
    private async void Paste_Click(object sender, RoutedEventArgs e)
    {
        try { var data = Clipboard.GetContent(); if (data.Contains(StandardDataFormats.Text)) LinkInput.Text = await data.GetTextAsync(); }
        catch (Exception) { Show("暂时无法读取剪贴板，请手动粘贴。", InfoBarSeverity.Warning); }
    }
    private async void Download_Click(object sender, RoutedEventArgs e)
    {
        if (QualityBox.SelectedItem is not MediaVariant quality || Results.SelectedItems.Count == 0) { Show("请选择至少一个条目。", InfoBarSeverity.Warning); return; }
        DownloadButton.IsEnabled = false;
        try
        {
            await App.Services.Queue.AddAsync(Results.SelectedItems.Cast<MediaItem>().ToArray(), quality.Quality, App.Services.Settings.DownloadDirectory);
            Show("已加入下载队列，可在左侧查看进度。重复的活动或已完成任务会自动跳过。", InfoBarSeverity.Success);
        }
        catch (Exception) { Show("任务保存失败，请检查本地目录权限。", InfoBarSeverity.Error); }
        finally { DownloadButton.IsEnabled = true; }
    }
    private void SelectAll_Click(object sender, RoutedEventArgs e) => Results.SelectAll();
    private void SelectNone_Click(object sender, RoutedEventArgs e) => Results.SelectedItems.Clear();
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
