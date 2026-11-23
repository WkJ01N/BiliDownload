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
    private CancellationTokenSource? estimating, paging;
    private readonly Queue<PlaylistCursor> pages = new();
    private string? qualityReference;
    private bool updatingQuality;
    private bool youtubeRecovery;
    private readonly Dictionary<string, IReadOnlyList<MediaVariant>> estimates = new();
    public sealed class ResultRow(MediaItem item, string fileName) : System.ComponentModel.INotifyPropertyChanged {
        public MediaItem Item { get; } = item;
        public string FileName { get; } = fileName;
        private string sizeText = "";
        public string SizeText { get => sizeText; set { sizeText = value; PropertyChanged?.Invoke(this, new(nameof(SizeText))); } }
        public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
        public string Id => Item.Id;
        public string Title => Item.Title;
        public string PartTitle => Item.PartTitle == Item.Title ? "" : Item.PartTitle;
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
    public void CancelPending() { parsing?.Cancel(); estimating?.Cancel(); paging?.Cancel(); }
    private async void Parse_Click(object sender, RoutedEventArgs e) => await ParseAsync(ReferenceEquals(sender, RetryButton) && clipboardMode);

    public async Task OpenClipboardAsync(ClipboardRequest request, bool fromNotification)
    {
        LinkInput.Text = string.Join(Environment.NewLine, request.Links);
        var catalogs = await ParseAsync(true, false);
        if (catalogs != null && fromNotification && ClipboardPolicy.ShouldAutoDownload(App.Services.Settings, request.Links.Length, catalogs))
        {
            if (QualityBox.SelectedItem is MediaVariant v && !WithinDefault(v)) {
                Show("没有可用的 1080p 及以下画质，请确认画质后手动下载。", InfoBarSeverity.Warning); return;
            }
            await DownloadSelectedAsync(true);
        }
    }

    private async Task<IReadOnlyList<MediaCatalog>?> ParseAsync(bool fromClipboard, bool preserveSelection = true)
    {
        CancelPending(); using var current = new CancellationTokenSource(); parsing = current; var ct = current.Token;
        var selectedPlaylists = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).Where(i => i.PlaylistId != null).ToArray();
        estimates.Clear(); qualityReference = null; pages.Clear(); MoreButton.Visibility = Visibility.Collapsed;
        clipboardMode = fromClipboard;
        var requestInput = LinkInput.Text;
        var sameInput = preserveSelection && lastInput == requestInput;
        var previousIds = sameInput ? Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).Select(i => i.Id).ToHashSet() : null;
        var previousQuality = sameInput ? (QualityBox.SelectedItem as MediaVariant)?.Quality : null;
        Busy.IsActive = true; ParseButton.IsEnabled = Results.IsEnabled = DownloadButton.IsEnabled = false; Notice.IsOpen = false;
        LoginButton.Visibility = RetryButton.Visibility = ComponentButton.Visibility = Visibility.Collapsed; RetryButton.IsEnabled = false;
        if (!sameInput) { ResultTitle.Visibility = ResultActions.Visibility = Results.Visibility = SizeSummary.Visibility = Visibility.Collapsed; estimates.Clear(); names.Clear(); resolvedItems = []; Results.ItemsSource = null; QualityBox.ItemsSource = null; }
        try
        {
            var catalogs = (await App.Services.Media.ResolveAsync(requestInput, ct)).ToList(); ct.ThrowIfCancellationRequested();
            if (sameInput && selectedPlaylists.Length != 0) {
                for (var i = 0; i < catalogs.Count; i++) {
                    var catalog = catalogs[i];
                    while (catalog.More is { } cursor && selectedPlaylists.Any(s => s.PlaylistId == cursor.Id && catalog.Items.All(j => j.Id != s.Id))) {
                        var next = await App.Services.Media.PageAsync(cursor, ct); ct.ThrowIfCancellationRequested();
                        catalog = catalog with { Items = catalog.Items.Concat(next.Items).DistinctBy(j => j.Id).ToArray(), More = next.More };
                    }
                    catalogs[i] = catalog;
                }
            }
            foreach (var catalog in catalogs) {
                if (catalog.More != null) pages.Enqueue(catalog.More);
                if (catalog.TargetItemId != null && catalog.InitialVariants != null) estimates[catalog.TargetItemId] = catalog.InitialVariants;
            }
            MoreButton.Visibility = pages.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            var items = fromClipboard ? ClipboardPolicy.VisibleItems(App.Services.Settings, catalogs).ToArray() : catalogs.SelectMany(c => c.Items).DistinctBy(i => i.Id).ToArray();
            if (items.Length == 0) throw new BiliException("链接指定的条目已不可用，请重新解析或选择其他内容。");
            if (!sameInput) names.Clear();
            resolvedItems = items;
            ResultTitle.Visibility = ResultActions.Visibility = Results.Visibility = Visibility.Visible;
            Results.ItemsSource = items.Select(i => new ResultRow(i, FileNaming.Render(i, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(i.Id)))).ToArray(); ResultTitle.Text = $"找到 {items.Length} 个条目"; lastInput = requestInput;
            var targets = catalogs.Where(c => c.TargetItemId != null).Select(c => c.TargetItemId).ToHashSet();
            foreach (var item in items.Where(i => previousIds?.Contains(i.Id) ?? targets.Contains(i.Id))) Results.SelectedItems.Add(Results.Items.Cast<ResultRow>().First(r => r.Id == item.Id));
            if (!fromClipboard && Results.SelectedItems.Count == 0 && previousIds == null && items[0].YoutubeId == null) Results.SelectedIndex = 0;
            ParseButton.Content = "获取画质…";
            var reference = Results.SelectedItems.Cast<ResultRow>().FirstOrDefault()?.Item;
            if (reference != null) await SetQualityAsync(reference, previousQuality, ct);
            else QualityBox.ItemsSource = null;
            DownloadButton.IsEnabled = reference != null;
            return catalogs;
        }
        catch (OperationCanceledException) { return null; }
        catch (Exception ex)
        {
            Show(ex is BiliException ? ex.Message : "解析失败，请检查网络后重试。", InfoBarSeverity.Error);
            Recovery(ex);
            RetryButton.Visibility = Visibility.Visible; return null;
        }
        finally
        {
            if (ReferenceEquals(parsing, current)) { parsing = null; ParseButton.Content = "解析链接"; Busy.IsActive = false; ParseButton.IsEnabled = Results.IsEnabled = RetryButton.IsEnabled = true; Estimate_Changed(this, null!); }
        }
    }
    private static bool WithinDefault(MediaVariant variant) => variant.FormatId != null ? variant.Height <= 1080 : variant.Quality <= 80;
    private void Recovery(Exception ex)
    {
        var kind = (ex as BiliException)?.Kind;
        youtubeRecovery = kind is BiliErrorKind.YoutubeSession or BiliErrorKind.YoutubeComponent;
        LoginButton.Visibility = AuthRecovery.NeedsLogin(kind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
        ComponentButton.Visibility = youtubeRecovery ? Visibility.Visible : Visibility.Collapsed;
        ComponentButton.Content = kind == BiliErrorKind.YoutubeSession ? "管理 YouTube 会话" : "安装 YouTube 组件";
    }
    private async Task SetQualityAsync(MediaItem item, int? preferred, CancellationToken ct)
    {
        if (!estimates.TryGetValue(item.Id, out var variants)) variants = await App.Services.Media.GetVariantsAsync(item, ct);
        ct.ThrowIfCancellationRequested();
        if (variants.Count == 0) throw new BiliException("当前没有可用的兼容画质。");
        estimates[item.Id] = variants;
        updatingQuality = true;
        try {
            qualityReference = item.Id; QualityBox.ItemsSource = variants;
            QualityBox.SelectedItem = variants.FirstOrDefault(v => v.Quality == preferred) ?? variants.Where(WithinDefault).MaxBy(v => v.Quality) ?? variants.MinBy(v => v.Quality);
        } finally { updatingQuality = false; }
    }
    private async void More_Click(object sender, RoutedEventArgs e)
    {
        if (!pages.TryPeek(out var cursor) || paging != null) return;
        using var current = new CancellationTokenSource(); paging = current; MoreButton.IsEnabled = false;
        try {
            var page = await App.Services.Media.PageAsync(cursor, current.Token); current.Token.ThrowIfCancellationRequested();
            pages.Dequeue(); if (page.More != null) pages.Enqueue(page.More);
            resolvedItems = resolvedItems.Concat(page.Items).DistinctBy(i => i.Id).ToArray();
            RefreshNames(); ResultTitle.Text = $"找到 {resolvedItems.Length} 个条目";
            MoreButton.Visibility = pages.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        } catch (OperationCanceledException) { }
        catch (Exception ex) { Show(ex is BiliException ? ex.Message : "无法加载播放列表，请重试。", InfoBarSeverity.Warning); Recovery(ex); }
        finally { paging = null; MoreButton.IsEnabled = true; }
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
            catch (Exception) { youtubeRecovery = false; ComponentButton.Content = "安装 / 配置 FFmpeg"; ComponentButton.Visibility = Visibility.Visible; Show("FFmpeg 不可用，请在设置中安装组件或配置工具后，再点击下载。原链接和选择已保留。", InfoBarSeverity.Warning); return; }
            if (App.Main.IsExiting) return;
            var pending = new List<(MediaItem Item, MediaVariant Variant)>();
            foreach (var item in selected) {
                if (!estimates.TryGetValue(item.Id, out var variants)) variants = await App.Services.Media.GetVariantsAsync(item);
                var actual = AtMost(variants, quality, item) ?? throw new BiliException("所选画质下没有兼容格式：" + item.Title);
                pending.Add((item, actual));
            }
            foreach (var (item, actual) in pending)
                await App.Services.Queue.AddAsync([item], item.YoutubeId == null && quality.FormatId == null ? quality.Quality : actual.Quality, App.Services.Settings.DownloadDirectory,
                    new Dictionary<string, string> { [item.Id] = FileNaming.Render(item, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(item.Id)) }, actual.FormatId);
            Show("已加入下载队列", InfoBarSeverity.Success);
            if (navigate) App.Main.OpenQueue();
        }
        catch (Exception ex) { Recovery(ex); Show(ex is BiliException ? ex.Message : "任务保存失败，请检查本地目录权限。", InfoBarSeverity.Error); }
        finally { DownloadButton.IsEnabled = true; }
    }
    private void Component_Click(object sender, RoutedEventArgs e) { if (youtubeRecovery) App.Main.OpenYoutubeSettings(); else App.Main.OpenSettings(); }
    private void SelectAll_Click(object sender, RoutedEventArgs e) => Results.SelectAll();
    private void SelectNone_Click(object sender, RoutedEventArgs e) => Results.SelectedItems.Clear();
    private async void Estimate_Changed(object sender, SelectionChangedEventArgs e) {
        if (updatingQuality || parsing != null || Results == null || SizeSummary == null) return;
        estimating?.Cancel();
        using var request = new CancellationTokenSource(); estimating = request; var ct = request.Token;
        var selected = Results.SelectedItems.Cast<ResultRow>().ToArray();
        foreach (var row in Results.Items.Cast<ResultRow>().Except(selected)) row.SizeText = "";
        SizeSummary.Visibility = selected.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        SizeSummary.Text = selected.Length == 0 ? "" : "正在估算选中内容的大小…";
        long total = 0; var unknown = 0;
        try {
            if (selected.Length == 0) { DownloadButton.IsEnabled = false; return; }
            var reference = selected[0].Item;
            if (qualityReference != reference.Id) await SetQualityAsync(reference, (QualityBox.SelectedItem as MediaVariant)?.Quality, ct);
            if (QualityBox.SelectedItem is not MediaVariant quality) return;
            DownloadButton.IsEnabled = true;
            foreach (var row in selected) {
                row.SizeText = "正在估算…";
                if (!estimates.TryGetValue(row.Id, out var variants)) {
                    try { variants = await App.Services.Media.GetVariantsAsync(row.Item, ct); ct.ThrowIfCancellationRequested(); estimates[row.Id] = variants; }
                    catch (OperationCanceledException) { throw; }
                    catch { row.SizeText = "大小未知"; unknown++; continue; }
                }
                ct.ThrowIfCancellationRequested();
                var actual = AtMost(variants, quality, row.Item);
                row.SizeText = actual == null ? "此画质上限无可用视频" : actual.Label + "  " + MediaSize.Format(actual.EstimatedBytes);
                if (actual?.EstimatedBytes is > 0) total += actual.EstimatedBytes.Value; else unknown++;
            }
            SizeSummary.Text = selected.Length == 0 ? "" : $"选中 {selected.Length} 项：{MediaSize.Format(total)}" + (unknown > 0 ? $"，另有 {unknown} 项大小未知" : "");
        } catch (OperationCanceledException) { }
        catch (Exception ex) { DownloadButton.IsEnabled = false; Recovery(ex); RetryButton.Visibility = Visibility.Visible; Show(ex is BiliException ? ex.Message : "无法获取画质，请重试。", InfoBarSeverity.Warning); }
        finally { if (ReferenceEquals(estimating, request)) estimating = null; }
    }
    private static MediaVariant? AtMost(IReadOnlyList<MediaVariant> variants, MediaVariant limit, MediaItem item)
    {
        if ((item.YoutubeId != null) == (limit.FormatId != null)) return variants.Where(v => v.Quality <= limit.Quality).MaxBy(v => v.Quality);
        int Height(MediaVariant v) => v.FormatId != null ? v.Height : v.Quality switch {
            <= 6 => 240, <= 16 => 360, <= 32 => 480, <= 74 => 720, <= 116 => 1080, <= 126 => 2160, _ => 4320 };
        return variants.Where(v => Height(v) <= Height(limit)).MaxBy(Height);
    }
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
