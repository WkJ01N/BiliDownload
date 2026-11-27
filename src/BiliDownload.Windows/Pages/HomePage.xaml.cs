using BiliDownload.Core;
using BiliDownload_Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
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
    private bool youtubeRecovery, audioRecovery, xAccountRecovery, youtubeSessionRecovery;

    public async Task ReparseAsync(string url, bool audio)
    {
        LinkInput.Text = url;
        await ParseAsync(false);
        if (audio) ModeBox.SelectedIndex = 2;
    }

    private readonly Dictionary<string, IReadOnlyList<MediaVariant>> estimates = new();
    private readonly Dictionary<string, IReadOnlyList<AudioTrack>> audioTracks = new();
    private readonly Dictionary<string, string> trackIds = new();
    private bool AudioMode(MediaItem item) => (item.X == null || item.X.HasAudio && item.X.Kind == "video") &&
        (ModeBox.SelectedIndex == 2 || ModeBox.SelectedIndex <= 0 && item.Music);
    private bool VideoQuality(MediaItem item) => !AudioMode(item) && (item.X == null || item.X.Kind == "video");
    private string Extension(MediaItem item) => AudioMode(item) ? Rate > 0 ? ".mp3" : Pick(item)?.Extension ?? ".m4a" :
        item.X?.Kind == "gif" && gifExports.Contains(item.Id) ? ".gif" : item.X?.Extension ?? ".mp4";
    private readonly HashSet<string> gifExports = new();
    private int Rate => ExportBox.SelectedIndex == 1 ? RateBox.SelectedIndex switch { 1 => 192, 2 => 128, _ => 320 } : 0;
    private AudioTrack? Pick(MediaItem item) => audioTracks.TryGetValue(item.Id, out var list)
        ? list.FirstOrDefault(t => t.Id == trackIds.GetValueOrDefault(item.Id)) ?? list.FirstOrDefault() : null;

    private async Task<AudioOutput> AudioForAsync(MediaItem item, CancellationToken ct)
    {
        if (!audioTracks.ContainsKey(item.Id))
        {
            var list = await App.Services.Media.AudioAsync(item, ct); ct.ThrowIfCancellationRequested(); audioTracks[item.Id] = list;
        }
        return new(Pick(item) ?? throw new BiliException("当前没有可用音轨。"), Rate);
    }

    private string NameFor(MediaItem item) => FileNaming.Render(item, App.Services.Settings.FileNameTemplate, names.GetValueOrDefault(item.Id),
        Extension(item));

    private void GifFormat_Click(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender;
        var id = (string)button.Tag;
        var menu = new MenuFlyout();
        foreach (var gif in new[] { false, true })
        {
            var option = new ToggleMenuFlyoutItem { Text = gif ? "GIF" : "原始 MP4", IsChecked = gifExports.Contains(id) == gif };
            option.Click += (_, _) => {
                if (gif) gifExports.Add(id); else gifExports.Remove(id);
                Estimate_Changed(this, null!);
            };
            menu.Items.Add(option);
        }
        menu.ShowAt(button);
    }

    private async void Track_Click(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender; var item = resolvedItems.First(i => i.Id == (string)button.Tag);
        try
        {
            await AudioForAsync(item, CancellationToken.None);
            var menu = new MenuFlyout();
            void Add(string label, string? id)
            {
                var option = new ToggleMenuFlyoutItem { Text = label, IsChecked = trackIds.GetValueOrDefault(item.Id) == id };
                option.Click += (_, _) => { if (id == null) trackIds.Remove(item.Id); else trackIds[item.Id] = id; Estimate_Changed(this, null!); }; menu.Items.Add(option);
            }
            Add("自动最高", null); foreach (var track in audioTracks[item.Id]) Add(track.Label, track.Id); menu.ShowAt(button);
        }
        catch (Exception ex) { Recovery(ex); Show(ex.Message, InfoBarSeverity.Warning); }
    }

    public sealed class ResultRow(MediaItem item, string fileName) : System.ComponentModel.INotifyPropertyChanged
    {
        public MediaItem Item { get; } = item;
        private string name = fileName;
        public string FileName { get => name; set { name = value; PropertyChanged?.Invoke(this, new(nameof(FileName))); } }
        private Visibility audioVisibility = Visibility.Collapsed;
        public Visibility GifVisibility => Item.X?.Kind == "gif" ? Visibility.Visible : Visibility.Collapsed;
        private string gifLabel = "动图格式：原始 MP4";
        public string GifLabel { get => gifLabel; set { gifLabel = value; PropertyChanged?.Invoke(this, new(nameof(GifLabel))); } }
        private string choice = "音质：自动最高";
        public string AudioChoice { get => choice; set { choice = value; PropertyChanged?.Invoke(this, new(nameof(AudioChoice))); } }
        public Visibility AudioVisibility { get => audioVisibility; set { audioVisibility = value; PropertyChanged?.Invoke(this, new(nameof(AudioVisibility))); } }
        private string sizeText = "";
        public string SizeText
        {
            get => sizeText;
            set
            {
                sizeText = value;
                PropertyChanged?.Invoke(this, new(nameof(SizeText)));
                PropertyChanged?.Invoke(this, new(nameof(SizeVisibility)));
            }
        }
        public Visibility SizeVisibility => string.IsNullOrWhiteSpace(sizeText) ? Visibility.Collapsed : Visibility.Visible;
        public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
        public string Id => Item.Id;
        public string Title => Item.X is { Kind: "markdown", Author.Length: > 0 } post ? $"{post.Author} (@{post.Handle})" : Item.Title;
        public string PostText => Item.X?.Text ?? "";
        public Visibility PostTextVisibility => PostText.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        public Visibility CoverVisibility => string.IsNullOrEmpty(Item.Cover) ? Visibility.Collapsed : Visibility.Visible;
        public string PartTitle => Item.X is { Kind: "markdown", Truncated: true } ? "正文（部分内容）" : Item.PartTitle == Item.Title ? "" : Item.PartTitle;
        public Visibility PartTitleVisibility => string.IsNullOrWhiteSpace(PartTitle) ? Visibility.Collapsed : Visibility.Visible;
        public string PlatformLabel => Item.X != null ? "X" : Item.Music ? "YT Music" : Item.YoutubeId != null ? "YouTube" : "Bilibili";
        public string KindLabel => Item.X?.Kind switch
        {
            "markdown" => "正文",
            "image" => "图片",
            "gif" => "动图",
            "video" => "视频",
            _ when Item.Music => "音乐",
            _ when Item.YoutubeId != null => "视频",
            _ => Item.Kind switch
            {
                MediaKind.Bangumi => "番剧",
                MediaKind.Course => "课程",
                _ => "投稿"
            }
        };
        public BitmapImage? CoverImage
        {
            get
            {
                if (string.IsNullOrWhiteSpace(Item.Cover)) return null;
                var normalized = Item.Cover.StartsWith("//", StringComparison.Ordinal) ? "https:" + Item.Cover : Item.Cover;
                return Uri.TryCreate(normalized, UriKind.Absolute, out var uri) ? new BitmapImage(uri) : null;
            }
        }
    }

    private void RefreshNames()
    {
        var ids = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Id).ToHashSet();
        Results.ItemsSource = resolvedItems.Select(i => new ResultRow(i, NameFor(i))).ToArray();
        foreach (var row in Results.Items.Cast<ResultRow>().Where(r => ids.Contains(r.Id))) Results.SelectedItems.Add(row);
    }

    private async void Rename_Click(object sender, RoutedEventArgs e)
    {
        var id = (string)((Button)sender).Tag;
        var item = resolvedItems.First(i => i.Id == id);
        var box = new TextBox { Text = Path.GetFileNameWithoutExtension(NameFor(item)), Header = "文件名（自动添加 " + Path.GetExtension(NameFor(item)) + "）", AcceptsReturn = false };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(box, "RenameFileName");
        var preview = new TextBlock { TextWrapping = TextWrapping.Wrap };
        void Update() => preview.Text = "实际文件名：" + FileNaming.Normalize(box.Text, Path.GetExtension(NameFor(item)));
        box.TextChanged += (_, _) => Update(); Update();
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(box); panel.Children.Add(preview);
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = "修改文件名", Content = panel, PrimaryButtonText = "保存", SecondaryButtonText = "恢复默认", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        var result = await DialogCoordinator.ShowAsync(dialog);
        if (result == ContentDialogResult.Primary) names[id] = Path.GetFileNameWithoutExtension(FileNaming.Normalize(box.Text, Extension(item)));
        else if (result == ContentDialogResult.Secondary) names.Remove(id);
        RefreshNames();
    }

    public HomePage() { InitializeComponent(); NavigationCacheMode = NavigationCacheMode.Enabled; }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        if (e.Parameter is string text && text != "home") LinkInput.Text = text;
        if (resolvedItems.Length > 0) RefreshNames();
        UpdateClearVisibility();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => CancelPending();
    public void CancelPending() { parsing?.Cancel(); estimating?.Cancel(); paging?.Cancel(); }

    private void LinkInput_TextChanged(object sender, TextChangedEventArgs e) => UpdateClearVisibility();
    private void UpdateClearVisibility()
    {
        if (ClearButton != null) ClearButton.Visibility = string.IsNullOrEmpty(LinkInput.Text) ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Clear_Click(object sender, RoutedEventArgs e) { LinkInput.Text = ""; UpdateClearVisibility(); }

    private async void Parse_Click(object sender, RoutedEventArgs e) => await ParseAsync(ReferenceEquals(sender, RetryButton) && clipboardMode);

    public async Task OpenClipboardAsync(ClipboardRequest request, bool fromNotification)
    {
        LinkInput.Text = string.Join(Environment.NewLine, request.Links);
        var catalogs = await ParseAsync(true, false);
        if (catalogs != null && fromNotification && ClipboardPolicy.ShouldAutoDownload(App.Services.Settings, request.Links.Length, catalogs))
        {
            if (Results.SelectedItems.Cast<ResultRow>().Any(r => VideoQuality(r.Item)) && QualityBox.SelectedItem is MediaVariant v && !WithinDefault(v))
            {
                Show("没有可用的 1080p 及以下画质，请确认画质后手动下载。", InfoBarSeverity.Warning); return;
            }
            await DownloadSelectedAsync(true);
        }
    }

    private async Task<IReadOnlyList<MediaCatalog>?> ParseAsync(bool fromClipboard, bool preserveSelection = true)
    {
        CancelPending(); using var current = new CancellationTokenSource(); parsing = current; var ct = current.Token;
        var selectedPlaylists = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).Where(i => i.PlaylistId != null).ToArray();
        estimates.Clear(); audioTracks.Clear(); qualityReference = null; pages.Clear(); MoreButton.Visibility = Visibility.Collapsed;
        clipboardMode = fromClipboard;
        var requestInput = LinkInput.Text;
        var sameInput = preserveSelection && lastInput == requestInput;
        var previousIds = sameInput ? Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).Select(i => i.Id).ToHashSet() : null;
        var previousQuality = sameInput ? (QualityBox.SelectedItem as MediaVariant)?.Quality : null;
        Busy.IsActive = true; ParseButton.IsEnabled = Results.IsEnabled = DownloadButton.IsEnabled = false; Notice.IsOpen = false;
        LoginButton.Visibility = RetryButton.Visibility = ComponentButton.Visibility = Visibility.Collapsed; RetryButton.IsEnabled = false;
        if (!sameInput)
        {
            ResultTitle.Visibility = ResultActions.Visibility = Results.Visibility = SizeSummary.Visibility = Visibility.Collapsed;
            EmptyGuide.Visibility = Visibility.Visible;
            estimates.Clear(); names.Clear(); resolvedItems = []; Results.ItemsSource = null; QualityBox.ItemsSource = null;
        }
        try
        {
            var catalogs = (await App.Services.Media.ResolveAsync(requestInput, ct)).ToList(); ct.ThrowIfCancellationRequested();
            if (sameInput && selectedPlaylists.Length != 0)
            {
                for (var i = 0; i < catalogs.Count; i++)
                {
                    var catalog = catalogs[i];
                    while (catalog.More is { } cursor && selectedPlaylists.Any(s => s.PlaylistId == cursor.Id && catalog.Items.All(j => j.Id != s.Id)))
                    {
                        var next = await App.Services.Media.PageAsync(cursor, ct); ct.ThrowIfCancellationRequested();
                        catalog = catalog with { Items = catalog.Items.Concat(next.Items).DistinctBy(j => j.Id).ToArray(), More = next.More };
                    }
                    catalogs[i] = catalog;
                }
            }
            foreach (var catalog in catalogs)
            {
                if (catalog.TargetItemId != null && catalog.InitialAudio != null) audioTracks[catalog.TargetItemId] = catalog.InitialAudio;
                if (catalog.More != null) pages.Enqueue(catalog.More);
                if (catalog.TargetItemId != null && catalog.InitialVariants != null) estimates[catalog.TargetItemId] = catalog.InitialVariants;
            }
            MoreButton.Visibility = pages.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            var items = fromClipboard ? ClipboardPolicy.VisibleItems(App.Services.Settings, catalogs).ToArray() : catalogs.SelectMany(c => c.Items).DistinctBy(i => i.Id).ToArray();
            if (items.Length == 0) throw new BiliException("链接指定的条目已不可用，请重新解析或选择其他内容。");
            if (!sameInput) names.Clear();
            if (!sameInput)
            {
                trackIds.Clear(); ModeBox.SelectedIndex = items.All(i => i.Music) ? 2 : items.All(i => !i.Music) ? 1 : 0;
                gifExports.Clear(); ExportBox.SelectedIndex = RateBox.SelectedIndex = 0;
            }
            items = items.OrderBy(i => i.Music).ToArray();
            resolvedItems = items;
            EmptyGuide.Visibility = Visibility.Collapsed;
            ResultTitle.Visibility = ResultActions.Visibility = Results.Visibility = Visibility.Visible;
            Results.ItemsSource = items.Select(i => new ResultRow(i, NameFor(i))).ToArray(); ResultTitle.Text = $"找到 {items.Length} 个条目"; lastInput = requestInput;
            var targets = catalogs.Where(c => c.TargetItemId != null).Select(c => c.TargetItemId).ToHashSet();
            var selected = catalogs.SelectMany(c => c.Items.Where(i => i.X != null && c.TargetItemId == null && i.X.Kind != "markdown")).Select(i => i.Id).ToHashSet();
            foreach (var item in items.Where(i => i.X?.Available != false && (previousIds?.Contains(i.Id) ?? (targets.Contains(i.Id) || selected.Contains(i.Id))))) Results.SelectedItems.Add(Results.Items.Cast<ResultRow>().First(r => r.Id == item.Id));
            if (!fromClipboard && Results.SelectedItems.Count == 0 && previousIds == null && items[0].YoutubeId == null && items[0].X == null) Results.SelectedIndex = 0;
            ParseButtonLabel.Text = "获取画质…";
            var reference = Results.SelectedItems.Cast<ResultRow>().FirstOrDefault(r => VideoQuality(r.Item))?.Item;
            if (reference != null && !AudioMode(reference)) await SetQualityAsync(reference, previousQuality, ct);
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
            if (ReferenceEquals(parsing, current)) { parsing = null; ParseButtonLabel.Text = "解析链接"; Busy.IsActive = false; ParseButton.IsEnabled = Results.IsEnabled = RetryButton.IsEnabled = true; Estimate_Changed(this, null!); }
        }
    }

    private static bool WithinDefault(MediaVariant variant) => variant.FormatId != null ? variant.Height <= 1080 : variant.Quality <= 80;

    private void Recovery(Exception ex)
    {
        var kind = (ex as BiliException)?.Kind;
        xAccountRecovery = kind == BiliErrorKind.XSession;
        youtubeSessionRecovery = kind == BiliErrorKind.YoutubeSession;
        audioRecovery = kind == BiliErrorKind.AudioComponent;
        youtubeRecovery = kind is BiliErrorKind.YoutubeSession or BiliErrorKind.YoutubeComponent or BiliErrorKind.XSession or BiliErrorKind.XComponent;
        LoginButton.Visibility = AuthRecovery.NeedsLogin(kind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
        ComponentButton.Visibility = youtubeRecovery ? Visibility.Visible : Visibility.Collapsed;
        if (kind == BiliErrorKind.AudioComponent) { ComponentButton.Content = "安装 / 配置 FFmpeg"; ComponentButton.Visibility = Visibility.Visible; return; }
        ComponentButton.Content = kind switch { BiliErrorKind.XSession => "登录 X", BiliErrorKind.YoutubeSession => "登录 YouTube", _ => "安装下载组件" };
    }

    private async Task SetQualityAsync(MediaItem item, int? preferred, CancellationToken ct)
    {
        if (!estimates.TryGetValue(item.Id, out var variants)) variants = await App.Services.Media.GetVariantsAsync(item, ct);
        ct.ThrowIfCancellationRequested();

        estimates[item.Id] = variants;
        updatingQuality = true;
        try
        {
            qualityReference = item.Id; QualityBox.ItemsSource = variants;
            QualityBox.SelectedItem = variants.FirstOrDefault(v => v.Quality == preferred) ?? variants.Where(WithinDefault).MaxBy(v => v.Quality) ?? variants.MinBy(v => v.Quality);
        }
        finally { updatingQuality = false; }
    }

    private async void More_Click(object sender, RoutedEventArgs e)
    {
        if (!pages.TryPeek(out var cursor) || paging != null) return;
        using var current = new CancellationTokenSource(); paging = current; MoreButton.IsEnabled = false;
        try
        {
            var page = await App.Services.Media.PageAsync(cursor, current.Token); current.Token.ThrowIfCancellationRequested();
            pages.Dequeue(); if (page.More != null) pages.Enqueue(page.More);
            resolvedItems = resolvedItems.Concat(page.Items).DistinctBy(i => i.Id).ToArray();
            RefreshNames(); ResultTitle.Text = $"找到 {resolvedItems.Length} 个条目";
            MoreButton.Visibility = pages.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { Show(ex is BiliException ? ex.Message : "无法加载播放列表，请重试。", InfoBarSeverity.Warning); Recovery(ex); }
        finally { paging = null; MoreButton.IsEnabled = true; }
    }

    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        LoginButton.IsEnabled = false;
        try
        {
            var result = await LoginFlow.ShowAsync(XamlRoot);
            if (AuthRecovery.LoginMessage(result) is { } message) Show(message, result.Outcome == LoginOutcome.Failed ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        }
        finally { LoginButton.IsEnabled = true; }
    }

    private async void Paste_Click(object sender, RoutedEventArgs e)
    {
        try { var content = Clipboard.GetContent(); if (content.Contains(StandardDataFormats.Text)) LinkInput.Text = await content.GetTextAsync(); }
        catch (Exception) { Show("暂时无法读取剪贴板，请手动粘贴。", InfoBarSeverity.Warning); }
    }

    private async void Download_Click(object sender, RoutedEventArgs e) => await DownloadSelectedAsync(false);

    private async Task DownloadSelectedAsync(bool navigate)
    {
        var selected = Results.SelectedItems.Cast<ResultRow>().Select(r => r.Item).ToArray();
        if (selected.Length == 0) return;
        DownloadButton.IsEnabled = false;
        try
        {
            var pending = new List<(MediaItem Item, int Quality, string? Format, AudioOutput? Audio)>();
            foreach (var item in selected)
            {
                if (AudioMode(item))
                {
                    var audio = await AudioForAsync(item, default);
                    if (audio.Track.Mixed && navigate) { Show(audio.Summary + "，请确认后下载。", InfoBarSeverity.Warning); return; }
                    await AudioExporter.CheckAsync(App.Services.ResolveFfmpeg(), audio, default);
                    pending.Add((item, 0, audio.Track.Id, audio));
                }
                else
                {
                    if (item.X is { } x && x.Kind != "video")
                    {
                        if (x.Kind == "gif" && gifExports.Contains(item.Id)) await AudioExporter.CheckGifAsync(App.Services.ResolveFfmpeg(), default);
                        pending.Add((item with { X = x with { ExportGif = x.Kind == "gif" && gifExports.Contains(item.Id) } }, 0, null, null));
                        continue;
                    }
                    try { if (item.X == null) await MediaDownloader.CheckFfmpegAsync(App.Services.ResolveFfmpeg(), default); }
                    catch (BiliException) { throw new BiliException("请在设置中安装 FFmpeg 后重试。", BiliErrorKind.AudioComponent); }
                    if (QualityBox.SelectedItem is not MediaVariant quality) throw new BiliException("请选择可用画质，或切换到音频。");
                    if (!estimates.TryGetValue(item.Id, out var variants)) variants = await App.Services.Media.GetVariantsAsync(item);
                    var actual = AtMost(variants, quality, item) ?? throw new BiliException("所选画质下没有兼容格式：" + item.Title);
                    pending.Add((item, item.X == null && item.YoutubeId == null && quality.FormatId == null ? quality.Quality : actual.Quality, actual.FormatId, null));
                }
            }
            if (App.Main.IsExiting) return;
            var settings = App.Services.Settings;
            foreach (var task in pending.OrderBy(t => t.Item.X?.Kind == "markdown"))
            {
                var item = task.Item;
                var dir = item.X?.DirectoryUnder(settings.DownloadDirectory, settings.XSortByType, task.Audio != null) ?? settings.DownloadDirectory;
                if (item.X is { Kind: "markdown" } doc && !settings.XSortByType)
                {
                    var related = App.Services.Queue.Snapshot().Where(t => t.Directory == dir && t.Item.X?.PostId == doc.PostId &&
                        pending.Any(s => s.Item.Id == t.Item.Id && s.Item.X?.Kind != "markdown" && s.Quality == t.Quality &&
                            s.Item.X?.OutputExtension == t.Item.X?.OutputExtension && s.Audio?.Track.Id == t.AudioOutput?.Track.Id &&
                            s.Audio?.Mp3Kbps == t.AudioOutput?.Mp3Kbps)).Select(t => t.Id.ToString()).ToArray();
                    item = item with { X = doc with { Related = related } };
                }
                await App.Services.Queue.AddAsync([item], task.Quality, dir, new Dictionary<string, string> { [item.Id] = NameFor(item) }, task.Format, task.Audio);
            }
            Show("已加入下载队列", InfoBarSeverity.Success);
            if (navigate) App.Main.OpenQueue();
        }
        catch (Exception ex) { Recovery(ex); Show(ex is BiliException ? ex.Message : "任务保存失败，请检查本地目录权限。", InfoBarSeverity.Error); }
        finally { DownloadButton.IsEnabled = true; }
    }

    private async void Component_Click(object sender, RoutedEventArgs e)
    {
        if (xAccountRecovery || youtubeSessionRecovery)
        {
            var result = await WebLoginWindow.ShowAsync(xAccountRecovery ? WebLoginPlatform.X : WebLoginPlatform.YouTube);
            if (result.Outcome == LoginOutcome.Success) Show("登录成功，请重新解析或重试。", InfoBarSeverity.Success);
            else if (result.Outcome == LoginOutcome.Failed && result.Message != null) Show(result.Message, InfoBarSeverity.Warning);
        }
        else if (youtubeRecovery) App.Main.OpenYoutubeSettings();
        else if (audioRecovery) App.Main.OpenAudioSettings();
        else App.Main.OpenSettings();
    }

    private void ResultContainer_Changing(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (!args.InRecycleQueue && args.Item is ResultRow row) args.ItemContainer.IsEnabled = row.Item.X?.Available != false;
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        foreach (var row in Results.Items.Cast<ResultRow>().Where(row => row.Item.X?.Available != false && !Results.SelectedItems.Contains(row))) Results.SelectedItems.Add(row);
    }

    private void SelectNone_Click(object sender, RoutedEventArgs e) => Results.SelectedItems.Clear();

    private async void Estimate_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (updatingQuality || parsing != null || Results == null || SizeSummary == null) return;
        estimating?.Cancel();
        using var request = new CancellationTokenSource(); estimating = request; var ct = request.Token;
        var selected = Results.SelectedItems.Cast<ResultRow>().ToArray();
        var anyAudio = resolvedItems.Any(AudioMode);
        ExportBox.Visibility = anyAudio ? Visibility.Visible : Visibility.Collapsed;
        RateBox.Visibility = anyAudio && ExportBox.SelectedIndex == 1 ? Visibility.Visible : Visibility.Collapsed;
        ModeBox.Visibility = resolvedItems.Any(i => i.X == null || i.X.HasAudio) ? Visibility.Visible : Visibility.Collapsed;
        QualityBox.Visibility = selected.Any(r => VideoQuality(r.Item)) ? Visibility.Visible : Visibility.Collapsed;
        foreach (var row in Results.Items.Cast<ResultRow>())
        {
            if (row.Item.X?.Kind == "gif") row.GifLabel = gifExports.Contains(row.Id) ? "动图格式：GIF" : "动图格式：原始 MP4";
            row.AudioVisibility = AudioMode(row.Item) ? Visibility.Visible : Visibility.Collapsed;
            row.FileName = NameFor(row.Item); row.SizeText = row.Item.X?.Available == false ? "此附件没有可下载的格式" : "";
        }
        SizeSummary.Visibility = selected.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        SizeSummary.Text = "正在估算选中内容的大小…"; DownloadButton.IsEnabled = false;
        long total = 0; var unknown = 0; bool ready = true;
        try
        {
            if (selected.Length == 0) return;
            var reference = selected.FirstOrDefault(row => VideoQuality(row.Item))?.Item;
            if (reference != null && qualityReference != reference.Id) await SetQualityAsync(reference, (QualityBox.SelectedItem as MediaVariant)?.Quality, ct);
            foreach (var row in selected)
            {
                long? bytes;
                if (row.Item.X is { } x && !VideoQuality(row.Item) && !AudioMode(row.Item))
                {
                    bytes = x.Kind == "markdown" ? System.Text.Encoding.UTF8.GetByteCount(x.Markdown ?? "") : x.Bytes;
                    row.SizeText = Extension(row.Item).TrimStart('.').ToUpperInvariant() + "  " + MediaSize.Format(bytes);
                }
                else if (AudioMode(row.Item))
                {
                    var audio = await AudioForAsync(row.Item, ct);
                    row.AudioChoice = trackIds.ContainsKey(row.Id) ? "音质：" + audio.Track.Codec : "音质：自动最高";
                    row.SizeText = audio.Summary; row.FileName = NameFor(row.Item); bytes = audio.EstimatedBytes;
                }
                else
                {
                    if (!estimates.TryGetValue(row.Id, out var variants)) { variants = await App.Services.Media.GetVariantsAsync(row.Item, ct); ct.ThrowIfCancellationRequested(); estimates[row.Id] = variants; }
                    var actual = QualityBox.SelectedItem is MediaVariant limit ? AtMost(variants, limit, row.Item) : null;
                    row.SizeText = actual == null ? "无兼容画质，可选择音频" : actual.DisplayLabel;
                    ready &= actual != null; bytes = actual?.EstimatedBytes;
                }
                if (bytes is > 0) total += bytes.Value; else unknown++;
            }
            DownloadButton.IsEnabled = ready;
            SizeSummary.Text = $"选中 {selected.Length} 项：{MediaSize.Format(total)}" + (unknown > 0 ? $"，另有 {unknown} 项大小未知" : "");
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { DownloadButton.IsEnabled = false; Recovery(ex); RetryButton.Visibility = Visibility.Visible; Show(ex is BiliException ? ex.Message : "无法获取画质，请重试。", InfoBarSeverity.Warning); }
        finally { if (ReferenceEquals(estimating, request)) estimating = null; }
    }

    private static MediaVariant? AtMost(IReadOnlyList<MediaVariant> variants, MediaVariant limit, MediaItem item)
    {
        if ((item.YoutubeId != null || item.X != null) == (limit.FormatId != null)) return variants.Where(v => v.Quality <= limit.Quality).MaxBy(v => v.Quality);
        int Height(MediaVariant v) => v.FormatId != null ? v.Height : v.Quality switch
        {
            <= 6 => 240, <= 16 => 360, <= 32 => 480, <= 74 => 720, <= 116 => 1080, <= 126 => 2160, _ => 4320
        };
        return variants.Where(v => Height(v) <= Height(limit)).MaxBy(Height);
    }

    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
