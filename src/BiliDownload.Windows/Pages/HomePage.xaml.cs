using BiliDownload.Core;
using BiliDownload_Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;

namespace BiliDownload_Windows.Pages;

public sealed partial class HomePage : Page
{
    private CancellationTokenSource? parsing;
    private string? lastInput;
    private bool clipboardMode;
    public HomePage() { InitializeComponent(); NavigationCacheMode = NavigationCacheMode.Enabled; }
    protected override void OnNavigatedTo(NavigationEventArgs e) { if (e.Parameter is string text && text != "home") LinkInput.Text = text; }
    protected override void OnNavigatedFrom(NavigationEventArgs e) => CancelPending();
    public void CancelPending() => parsing?.Cancel();
    private async void Parse_Click(object sender, RoutedEventArgs e) => await ParseAsync(ReferenceEquals(sender, RetryButton) && clipboardMode);

    public async Task OpenClipboardAsync(ClipboardRequest request, bool fromNotification)
    {
        LinkInput.Text = string.Join(Environment.NewLine, request.Links);
        var catalogs = await ParseAsync(true, false);
        if (catalogs != null && fromNotification && ClipboardPolicy.ShouldAutoDownload(App.Services.Settings, request.Links.Length, catalogs))
        {
            if (QualityBox.SelectedItem is MediaVariant { Quality: > 80 }) {
                Show("没有可用的 1080p 及以下画质，请确认画质后手动下载。", InfoBarSeverity.Warning); return;
            }
            await DownloadSelectedAsync(true);
        }
    }

    private async Task<IReadOnlyList<MediaCatalog>?> ParseAsync(bool fromClipboard, bool preserveSelection = true)
    {
        parsing?.Cancel(); using var current = new CancellationTokenSource(); parsing = current; var ct = current.Token;
        clipboardMode = fromClipboard;
        var requestInput = LinkInput.Text;
        var sameInput = preserveSelection && lastInput == requestInput;
        var previousIds = sameInput ? Results.SelectedItems.Cast<MediaItem>().Select(i => i.Id).ToHashSet() : null;
        var previousQuality = sameInput ? (QualityBox.SelectedItem as MediaVariant)?.Quality : null;
        Busy.IsActive = true; ParseButton.IsEnabled = Results.IsEnabled = DownloadButton.IsEnabled = false; Notice.IsOpen = false;
        LoginButton.Visibility = RetryButton.Visibility = ComponentButton.Visibility = Visibility.Collapsed; RetryButton.IsEnabled = false;
        if (!sameInput) { Results.ItemsSource = null; QualityBox.ItemsSource = null; }
        try
        {
            var catalogs = await App.Services.Bili.ResolveAsync(requestInput, ct); ct.ThrowIfCancellationRequested();
            var items = fromClipboard ? ClipboardPolicy.VisibleItems(App.Services.Settings, catalogs).ToArray() : catalogs.SelectMany(c => c.Items).DistinctBy(i => i.Id).ToArray();
            if (items.Length == 0) throw new BiliException("链接指定的条目已不可用，请重新解析或选择其他内容。");
            Results.ItemsSource = items; ResultTitle.Text = $"找到 {items.Length} 个条目 · 请选择下载内容"; lastInput = requestInput;
            var targets = catalogs.Where(c => c.TargetItemId != null).Select(c => c.TargetItemId).ToHashSet();
            foreach (var item in items.Where(i => previousIds?.Contains(i.Id) ?? targets.Contains(i.Id))) Results.SelectedItems.Add(item);
            if (!fromClipboard && Results.SelectedItems.Count == 0 && previousIds == null) Results.SelectedItem = items[0];
            var reference = Results.SelectedItems.Cast<MediaItem>().FirstOrDefault() ?? items[0];
            var variants = await App.Services.Bili.GetVariantsAsync(reference, ct); ct.ThrowIfCancellationRequested();
            if (variants.Count == 0) throw new BiliException("当前没有可用的兼容清晰度。");
            QualityBox.ItemsSource = variants;
            QualityBox.SelectedItem = variants.FirstOrDefault(v => v.Quality == previousQuality) ?? variants.Where(v => v.Quality <= 80).MaxBy(v => v.Quality) ?? variants.MinBy(v => v.Quality);
            DownloadButton.IsEnabled = true;
            return catalogs;
        }
        catch (OperationCanceledException) { return null; }
        catch (Exception ex)
        {
            Show(ex is BiliException ? ex.Message : "解析失败，请检查网络后重试。", InfoBarSeverity.Error);
            LoginButton.Visibility = AuthRecovery.NeedsLogin((ex as BiliException)?.Kind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
            RetryButton.Visibility = Visibility.Visible; return null;
        }
        finally
        {
            if (ReferenceEquals(parsing, current)) { parsing = null; Busy.IsActive = false; ParseButton.IsEnabled = Results.IsEnabled = RetryButton.IsEnabled = true; }
        }
    }
    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        LoginButton.IsEnabled = false;
        try {
            var result = await LoginFlow.ShowAsync(XamlRoot);
            if (AuthRecovery.LoginMessage(result) is { } message) Show(message, result.Outcome == LoginOutcome.Failed ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        } finally { LoginButton.IsEnabled = true; }
    }
    private async void Paste_Click(object sender, RoutedEventArgs e)
    {
        try { var content = Clipboard.GetContent(); if (content.Contains(StandardDataFormats.Text)) LinkInput.Text = await content.GetTextAsync(); }
        catch (Exception) { Show("暂时无法读取剪贴板，请手动粘贴。", InfoBarSeverity.Warning); }
    }
    private async void Download_Click(object sender, RoutedEventArgs e) => await DownloadSelectedAsync(false);
    private async Task DownloadSelectedAsync(bool navigate)
    {
        if (QualityBox.SelectedItem is not MediaVariant quality || Results.SelectedItems.Count == 0) { Show("请选择至少一个条目。", InfoBarSeverity.Warning); return; }
        var selected = Results.SelectedItems.Cast<MediaItem>().ToArray(); DownloadButton.IsEnabled = false;
        try
        {
            try { await MediaDownloader.CheckFfmpegAsync(App.Services.ResolveFfmpeg(), default); }
            catch (Exception) { ComponentButton.Visibility = Visibility.Visible; Show("FFmpeg 不可用，请在设置中安装组件或配置工具后，再点击下载。原链接和选择已保留。", InfoBarSeverity.Warning); return; }
            if (App.Main.IsExiting) return;
            await App.Services.Queue.AddAsync(selected, quality.Quality, App.Services.Settings.DownloadDirectory);
            Show("已加入下载队列。重复的活动或已完成任务会自动跳过。", InfoBarSeverity.Success);
            if (navigate) App.Main.OpenQueue();
        }
        catch (Exception) { Show("任务保存失败，请检查本地目录权限。", InfoBarSeverity.Error); }
        finally { DownloadButton.IsEnabled = true; }
    }
    private void Component_Click(object sender, RoutedEventArgs e) => App.Main.OpenSettings();
    private void SelectAll_Click(object sender, RoutedEventArgs e) => Results.SelectAll();
    private void SelectNone_Click(object sender, RoutedEventArgs e) => Results.SelectedItems.Clear();
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
