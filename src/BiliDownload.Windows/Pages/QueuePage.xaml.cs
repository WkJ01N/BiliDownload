using BiliDownload.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using System.Diagnostics;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using BiliDownload_Windows.ViewModels;
using System.Collections.ObjectModel;
using BiliDownload_Windows.Services;

namespace BiliDownload_Windows.Pages;

public sealed partial class QueuePage : Page
{
    private bool completed;
    private readonly ObservableCollection<DownloadTaskViewModel> rows = new();
    private bool smoke;
    private bool selectionMode;
    private CancellationTokenSource? noticeCts;

    public void ShowSmokeState(DownloadState state)
    {
        smoke = true;
        rows.Clear();
        var item = new MediaItem("video:smoke", "响应式界面验证：这是一条较长的视频标题，用于检查窄窗口中的文字换行和操作菜单", "第一部分  下载后可复制文件、打开文件夹或播放", "", MediaKind.Video, 1, "BV1xx411c7mD", 1, 0, 1, "https://www.bilibili.com/");
        rows.Add(new(new(Guid.NewGuid(), item, 80, "", state, state == DownloadState.Completed ? 100 : 42, state == DownloadState.Failed ? "请求受限，可登录或稍后重试" : state.ToString(), FailureKind: state == DownloadState.Failed ? BiliErrorKind.RequestRestricted : null)));
        Empty.Visibility = Visibility.Collapsed;
        if (completed)
        {
            ActiveBadge.Visibility = Visibility.Collapsed;
            CompletedCountText.Text = $"已完成 {(state == DownloadState.Completed ? 1 : 0)}";
            BatchSelectButton.Visibility = state == DownloadState.Completed ? Visibility.Visible : Visibility.Collapsed;
        }
        else
        {
            ActiveBadge.Visibility = Visibility.Visible;
            ActiveCountText.Text = $"进行中 {(state is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging ? 1 : 0)}";
            CompletedCountText.Text = "队列 1";
            BatchSelectButton.Visibility = Visibility.Collapsed;
        }
    }

    public QueuePage()
    {
        InitializeComponent();
        Tasks.ItemsSource = rows;
        UiMotion.BindNoticeHost(NoticeHost, Notice, NoticeProgress, () => { noticeCts?.Cancel(); noticeCts?.Dispose(); noticeCts = null; });
        Loaded += (_, _) => { App.Services.Queue.Changed += Refresh; Refresh(); };
        Unloaded += (_, _) => { App.Services.Queue.Changed -= Refresh; noticeCts?.Cancel(); };
        ActualThemeChanged += (_, _) => { foreach (var row in rows) row.RefreshTheme(); };
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        completed = e.Parameter as string == "completed";
        selectionMode = false;
        PageTitle.Text = completed ? "已完成" : "下载队列";
        EmptyTitle.Text = completed ? "暂无已完成内容" : "暂无下载任务";
        EmptySubtitle.Text = completed ? "内容下载并处理完成后，可在此批量管理、复制文件、打开目录或直接播放" : "未完成的下载任务会显示在这里，完成后自动归入「已完成」";
        UpdateBatchChrome();
        Refresh();
    }

    private void Refresh() => DispatcherQueue.TryEnqueue(() =>
    {
        if (smoke) return;
        var all = App.Services.Queue.Snapshot();
        var items = all.Where(t => completed ? t.State == DownloadState.Completed : t.State != DownloadState.Completed).ToArray();

        if (completed)
        {
            ActiveBadge.Visibility = Visibility.Collapsed;
            CompletedCountText.Text = $"已完成 {items.Length}";
            BatchSelectButton.Visibility = items.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        else
        {
            ActiveBadge.Visibility = Visibility.Visible;
            ActiveCountText.Text = $"进行中 {items.Count(t => t.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging)}";
            CompletedCountText.Text = $"队列 {items.Length}";
            BatchSelectButton.Visibility = Visibility.Collapsed;
        }

        foreach (var row in rows.Where(r => items.All(i => i.Id != r.Data.Id)).ToArray())
        {
            row.SelectionChanged -= Row_SelectionChanged;
            rows.Remove(row);
        }
        for (var i = 0; i < items.Length; i++)
        {
            var row = rows.FirstOrDefault(r => r.Data.Id == items[i].Id);
            if (row == null)
            {
                row = new(items[i]);
                row.SelectionChanged += Row_SelectionChanged;
                row.SetSelectionMode(completed && selectionMode);
                rows.Insert(i, row);
            }
            else
            {
                row.Update(items[i]);
                row.SetSelectionMode(completed && selectionMode);
            }
        }
        Empty.Visibility = items.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (completed && items.Length == 0 && selectionMode) selectionMode = false;
        UpdateBatchChrome();
    });

    private void Row_SelectionChanged(object? sender, EventArgs e) => UpdateBatchChrome();

    private void UpdateBatchChrome()
    {
        var wasVisible = BatchBar.Visibility == Visibility.Visible;
        if (!completed || !selectionMode)
        {
            if (wasVisible) UiMotion.AnimateHide(BatchBar, toOffsetY: -4, durationMs: 160);
            BatchSelectButton.Content = "批量选择";
            foreach (var row in rows) row.SetSelectionMode(false);
            return;
        }
        foreach (var row in rows) row.SetSelectionMode(true);
        if (!wasVisible) UiMotion.AnimateReveal(BatchBar, fromOffsetY: -6, durationMs: 200);
        BatchSelectButton.Content = "退出多选";
        var count = rows.Count(r => r.IsSelected);
        SelectedCountText.Text = $"已选 {count} 项";
        BatchCopyButton.IsEnabled = count > 0;
        BatchRemoveButton.IsEnabled = count > 0;
    }

    private void BatchSelect_Click(object sender, RoutedEventArgs e)
    {
        if (!completed || rows.Count == 0) return;
        selectionMode = !selectionMode;
        UpdateBatchChrome();
    }

    private void BatchExit_Click(object sender, RoutedEventArgs e)
    {
        selectionMode = false;
        UpdateBatchChrome();
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        foreach (var row in rows) row.IsSelected = true;
    }

    private void SelectNone_Click(object sender, RoutedEventArgs e)
    {
        foreach (var row in rows) row.IsSelected = false;
    }

    private void Tasks_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (completed && selectionMode && e.ClickedItem is DownloadTaskViewModel row)
            row.IsSelected = !row.IsSelected;
    }

    private async void BatchCopy_Click(object sender, RoutedEventArgs e)
    {
        var selected = rows.Where(r => r.IsSelected).ToArray();
        if (selected.Length == 0) return;
        try
        {
            var files = new List<IStorageItem>();
            foreach (var row in selected)
            {
                if (row.Data.FilePath is { } path && File.Exists(path))
                    files.Add(await StorageFile.GetFileFromPathAsync(path));
            }
            if (files.Count == 0) throw new BiliException("所选条目找不到有效的本地文件。");
            var data = new DataPackage { RequestedOperation = DataPackageOperation.Copy };
            data.SetStorageItems(files);
            Clipboard.SetContent(data);
            Clipboard.Flush();
            Show($"已复制 {files.Count} 个文件。", InfoBarSeverity.Success);
        }
        catch (Exception ex)
        {
            Show(ex is BiliException ? ex.Message : "批量复制失败，请检查文件是否被移动或删除。", InfoBarSeverity.Warning);
        }
    }

    private async void BatchRemove_Click(object sender, RoutedEventArgs e)
    {
        var targets = rows.Where(r => r.IsSelected).Select(r => r.Data).ToArray();
        if (targets.Length == 0) return;
        await ConfirmRemoveAsync(targets);
    }

    private void GoHome_Click(object sender, RoutedEventArgs e) => App.Main.OpenHome();
    private static DownloadTaskData Item(object sender) => ((DownloadTaskViewModel)((FrameworkElement)sender).Tag).Data;
    private async void Pause_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Paused);
    private async void Resume_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Queued);
    private async void Cancel_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Canceled);

    private async Task Change(object sender, DownloadState state)
    {
        try { await App.Services.Queue.SetStateAsync(Item(sender).Id, state); }
        catch (Exception) { Show("无法更新任务状态，请重试。", InfoBarSeverity.Error); }
    }

    private async void Remove_Click(object sender, RoutedEventArgs e) => await ConfirmRemoveAsync([Item(sender)]);

    private async Task ConfirmRemoveAsync(IReadOnlyList<DownloadTaskData> targets)
    {
        var fileTargets = targets.Count(t => t.State == DownloadState.Completed && !string.IsNullOrWhiteSpace(t.FilePath) && File.Exists(t.FilePath));
        var panel = new StackPanel { Spacing = 10 };
        panel.Children.Add(new TextBlock
        {
            Text = fileTargets > 0
                ? (targets.Count > 1 ? $"将移除选中的 {targets.Count} 条下载记录。" : "将移除此下载记录。")
                : "进行中的下载会停止，并清理该任务的临时缓存。已下载的文件会保留。",
            TextWrapping = TextWrapping.Wrap,
        });
        CheckBox? deleteBox = null;
        if (fileTargets > 0)
        {
            deleteBox = new CheckBox
            {
                Content = fileTargets > 1 ? $"同时删除已下载的本地文件（{fileTargets} 个）" : "同时删除已下载的本地文件",
                IsChecked = false,
            };
            panel.Children.Add(deleteBox);
        }
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = targets.Count > 1 ? $"移除 {targets.Count} 条记录？" : "移除任务记录？",
            Content = panel,
            PrimaryButtonText = "移除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await DialogCoordinator.ShowAsync(dialog) != ContentDialogResult.Primary) return;

        var deleteFiles = deleteBox?.IsChecked == true;
        var fileFailures = 0;
        var failedAny = false;
        foreach (var target in targets)
        {
            try
            {
                await App.Services.Queue.RemoveAsync(target.Id, deleteFiles);
            }
            catch (BiliException)
            {
                fileFailures++;
            }
            catch
            {
                failedAny = true;
            }
        }
        if (selectionMode && targets.Count > 1)
        {
            selectionMode = false;
            UpdateBatchChrome();
        }
        if (failedAny) Show("部分记录移除失败，请重试。", InfoBarSeverity.Error);
        else if (fileFailures > 0) Show($"记录已移除，有 {fileFailures} 个本地文件正在使用或无权删除。", InfoBarSeverity.Warning);
        else if (deleteFiles && fileTargets > 0) Show("记录与所选本地文件已删除。", InfoBarSeverity.Success);
    }

    private async void YoutubeSettings_Click(object sender, RoutedEventArgs e)
    {
        var kind = Item(sender).FailureKind;
        if (kind is BiliErrorKind.XSession or BiliErrorKind.YoutubeSession)
        {
            var result = await WebLoginWindow.ShowAsync(kind == BiliErrorKind.XSession ? WebLoginPlatform.X : WebLoginPlatform.YouTube);
            if (result.Outcome == LoginOutcome.Success)
            {
                Show("登录成功，可点击「继续 / 重试」。", InfoBarSeverity.Success);
                Refresh();
            }
            else if (result.Outcome == LoginOutcome.Failed && result.Message != null)
            {
                Show(result.Message, InfoBarSeverity.Warning);
            }
        }
        else if (kind == BiliErrorKind.XComponent)
        {
            App.Main.OpenYoutubeSettings();
        }
        else
        {
            App.Main.OpenYoutubeSettings();
        }
    }

    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        var result = await LoginFlow.ShowAsync(XamlRoot);
        if (result.Outcome == LoginOutcome.Success) Refresh();
        if (AuthRecovery.LoginMessage(result, forTask: true) is { } message) Show(message, result.Outcome == LoginOutcome.Success ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
    }

    private void AudioSettings_Click(object sender, RoutedEventArgs e) => App.Main.OpenAudioSettings();

    private async void Reparse_Click(object sender, RoutedEventArgs e)
    {
        var task = Item(sender);
        await App.Main.ReparseAsync(task.Item.PageUrl, task.Item.X == null || task.AudioOutput != null);
    }

    private async void Copy_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(ExistingPath(sender));
            var data = new DataPackage { RequestedOperation = DataPackageOperation.Copy };
            data.SetStorageItems([file]); Clipboard.SetContent(data); Clipboard.Flush();
            Show("已复制文件。", InfoBarSeverity.Success);
        }
        catch (Exception ex) { Show(ex is BiliException ? ex.Message : "文件无法复制，请检查文件是否被移动或删除。", InfoBarSeverity.Warning); }
    }

    private void Folder_Click(object sender, RoutedEventArgs e)
    {
        try { var path = ExistingPath(sender); Process.Start(new ProcessStartInfo(Path.GetDirectoryName(path)!) { UseShellExecute = true }); }
        catch (Exception) { Show("完成下载后才能打开文件夹，请确认文件仍然存在。", InfoBarSeverity.Warning); }
    }

    private void Open_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo(ExistingPath(sender)) { UseShellExecute = true }); }
        catch (Exception) { Show("暂时无法打开文件，请检查默认打开方式。", InfoBarSeverity.Warning); }
    }

    private static string ExistingPath(object sender)
    {
        var item = Item(sender);
        if (item.State != DownloadState.Completed || item.FilePath == null || !File.Exists(item.FilePath)) throw new BiliException("文件尚未下载完成，或文件已被移动、删除。");
        return item.FilePath;
    }

    private void Show(string text, InfoBarSeverity severity) =>
        UiMotion.ShowAnimatedNotice(NoticeHost, Notice, NoticeProgress, text, severity, hasActions: false, noticeCts, c => noticeCts = c);
}
