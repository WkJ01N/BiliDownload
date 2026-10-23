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
    private readonly Dictionary<string, string> names = new();
    private MediaItem[] resolvedItems = [];
    private CancellationTokenSource? estimating;
    private readonly Dictionary<string, IReadOnlyList<MediaVariant>> estimates = new();
    public sealed class ResultRow(MediaItem item, string fileName) : System.ComponentModel.INotifyPropertyChanged {
        public MediaItem Item { get; } = item;
        public string FileName { get; } = fileName;
        private string sizeText = "选择后估算大小";
        public string SizeText { get => sizeText; set { sizeText = value; PropertyChanged?.Invoke(this, new(nameof(SizeText))); } }
        public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
        public string Id => Item.Id;
        public string Title => Item.Title;
        public string PartTitle => Item.PartTitle;
        public string Cover => Item.Cover;
    }
    private void RefreshNames() {
        var ids = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Id).ToHashSet();
        Results.ItemsSource = resolvedItems.Select(i => new ResultRow(i, FileNaming.Render(i, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(i.Id)))).ToArray();
        foreach (var row in Results.Items.Cast<ResultRow>().Where(r => ids.Contains(r.Id))) Results.SelectedItems.Add(row);
    }
    private async void Rename_Click(object sender, RoutedEventArgs e) {
        var id = (string)((Button)sender).Tag;
        var item = resolvedItems.First(i => i.Id == id);
        var box = new TextBox { Text = Path.GetFileNameWithoutExtension(FileNaming.Render(item, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(id))), Header = "文件名（自动添加 .mp4）", AcceptsReturn = false };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(box, "RenameFileName");
        var preview = new TextBlock { TextWrapping = TextWrapping.Wrap };
        void Update() => preview.Text = "实际文件名：" + FileNaming.Normalize(box.Text);
        box.TextChanged += (_, _) => Update(); Update();
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(box); panel.Children.Add(preview);
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = "修改文件名", Content = panel, PrimaryButtonText = "保存", SecondaryButtonText = "恢复默认", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        var result = await DialogCoordinator.ShowAsync(dialog);
        if (result == ContentDialogResult.Primary) names[id] = FileNaming.Normalize(box.Text);
        else if (result == ContentDialogResult.Secondary) names.Remove(id);
        RefreshNames();
    }
    public HomePage() { InitializeComponent(); NavigationCacheMode = NavigationCacheMode.Enabled; }
    protected override void OnNavigatedTo(NavigationEventArgs e) { if (e.Parameter is string text && text != "home") LinkInput.Text = text; if (resolvedItems.Length > 0) RefreshNames(); }
    protected override void OnNavigatedFrom(NavigationEventArgs e) => CancelPending();
    public void CancelPending() { parsing?.Cancel(); estimating?.Cancel(); }
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
        estimates.Clear();
        clipboardMode = fromClipboard;
        var requestInput = LinkInput.Text;
        var sameInput = preserveSelection && lastInput == requestInput;
        var previousIds = sameInput ? Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).Select(i => i.Id).ToHashSet() : null;
        var previousQuality = sameInput ? (QualityBox.SelectedItem as MediaVariant)?.Quality : null;
        Busy.IsActive = true; ParseButton.IsEnabled = Results.IsEnabled = DownloadButton.IsEnabled = false; Notice.IsOpen = false;
        LoginButton.Visibility = RetryButton.Visibility = ComponentButton.Visibility = Visibility.Collapsed; RetryButton.IsEnabled = false;
        if (!sameInput) { estimates.Clear(); names.Clear(); resolvedItems = []; Results.ItemsSource = null; QualityBox.ItemsSource = null; }
        try
        {
            var catalogs = await App.Services.Bili.ResolveAsync(requestInput, ct); ct.ThrowIfCancellationRequested();
            var items = fromClipboard ? ClipboardPolicy.VisibleItems(App.Services.Settings, catalogs).ToArray() : catalogs.SelectMany(c => c.Items).DistinctBy(i => i.Id).ToArray();
            if (items.Length == 0) throw new BiliException("链接指定的条目已不可用，请重新解析或选择其他内容。");
            if (!sameInput) names.Clear();
            resolvedItems = items;
            Results.ItemsSource = items.Select(i => new ResultRow(i, FileNaming.Render(i, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(i.Id)))).ToArray(); ResultTitle.Text = $"找到 {items.Length} 个条目 · 请选择下载内容"; lastInput = requestInput;
            var targets = catalogs.Where(c => c.TargetItemId != null).Select(c => c.TargetItemId).ToHashSet();
            foreach (var item in items.Where(i => previousIds?.Contains(i.Id) ?? targets.Contains(i.Id))) Results.SelectedItems.Add(Results.Items.Cast<ResultRow>().First(r => r.Id == item.Id));
            if (!fromClipboard && Results.SelectedItems.Count == 0 && previousIds == null) Results.SelectedIndex = 0;
            var reference = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).FirstOrDefault() ?? items[0];
            var variants = await App.Services.Bili.GetVariantsAsync(reference, ct); ct.ThrowIfCancellationRequested();
            if (variants.Count == 0) throw new BiliException("当前没有可用的兼容清晰度。");
            estimates[reference.Id] = variants;
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
            if (ReferenceEquals(parsing, current)) { parsing = null; Busy.IsActive = false; ParseButton.IsEnabled = Results.IsEnabled = RetryButton.IsEnabled = true; Estimate_Changed(this, null!); }
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
        var selected = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).ToArray(); DownloadButton.IsEnabled = false;
        try
        {
            try { await MediaDownloader.CheckFfmpegAsync(App.Services.ResolveFfmpeg(), default); }
            catch (Exception) { ComponentButton.Visibility = Visibility.Visible; Show("FFmpeg 不可用，请在设置中安装组件或配置工具后，再点击下载。原链接和选择已保留。", InfoBarSeverity.Warning); return; }
            if (App.Main.IsExiting) return;
            await App.Services.Queue.AddAsync(selected, quality.Quality, App.Services.Settings.DownloadDirectory, selected.ToDictionary(i => i.Id, i => FileNaming.Render(i, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(i.Id))));
            Show("已加入下载队列。重复的活动或已完成任务会自动跳过。", InfoBarSeverity.Success);
            if (navigate) App.Main.OpenQueue();
        }
        catch (Exception) { Show("任务保存失败，请检查本地目录权限。", InfoBarSeverity.Error); }
        finally { DownloadButton.IsEnabled = true; }
    }
    private void Component_Click(object sender, RoutedEventArgs e) => App.Main.OpenSettings();
    private void SelectAll_Click(object sender, RoutedEventArgs e) => Results.SelectAll();
    private void SelectNone_Click(object sender, RoutedEventArgs e) => Results.SelectedItems.Clear();
    private async void Estimate_Changed(object sender, SelectionChangedEventArgs e) {
        estimating?.Cancel();
        if (parsing != null) return;
        if (QualityBox?.SelectedItem is not MediaVariant quality || Results == null || SizeSummary == null) return;
        using var request = new CancellationTokenSource(); estimating = request; var ct = request.Token;
        var selected = Results.SelectedItems.Cast<ResultRow>().ToArray();
        foreach (var row in Results.Items.Cast<ResultRow>().Except(selected)) row.SizeText = "选择后估算大小";
        SizeSummary.Text = selected.Length == 0 ? "选择条目后显示大小；最终文件可能略有差异。" : "正在估算选中内容的大小…";
        long total = 0; var unknown = 0;
        try {
            foreach (var row in selected) {
                row.SizeText = "正在估算…";
                if (!estimates.TryGetValue(row.Id, out var variants)) {
                    try { variants = await App.Services.Bili.GetVariantsAsync(row.Item, ct); ct.ThrowIfCancellationRequested(); estimates[row.Id] = variants; }
                    catch (OperationCanceledException) { throw; }
                    catch { row.SizeText = "大小未知，可重试解析"; unknown++; continue; }
                }
                ct.ThrowIfCancellationRequested();
                var actual = variants.Where(v => v.Quality <= quality.Quality).MaxBy(v => v.Quality);
                row.SizeText = actual == null ? "此画质上限无可用视频" : actual.Label + " · " + MediaSize.Format(actual.EstimatedBytes);
                if (actual?.EstimatedBytes is > 0) total += actual.EstimatedBytes.Value; else unknown++;
            }
            SizeSummary.Text = selected.Length == 0 ? "选择条目后显示大小。" : $"选中 {selected.Length} 项：{MediaSize.Format(total)}" + (unknown > 0 ? $"，另有 {unknown} 项大小未知" : "") + "。大小为音视频估算值，最终文件可能略有差异。";
        } catch (OperationCanceledException) { }
        finally { if (ReferenceEquals(estimating, request)) estimating = null; }
    }
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
