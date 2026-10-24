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
    public void ShowSmokeState(DownloadState state)
    {
        smoke = true; rows.Clear();
        var item = new MediaItem("video:smoke", "响应式界面验证：这是一条较长的视频标题，用于检查窄窗口中的文字换行和操作菜单", "第一部分  下载后可复制文件、打开文件夹或播放", "", MediaKind.Video, 1, "BV1xx411c7mD", 1, 0, 1, "https://www.bilibili.com/");
        rows.Add(new(new(Guid.NewGuid(), item, 80, "", state, state == DownloadState.Completed ? 100 : 42, state == DownloadState.Failed ? "请求受限，可登录或稍后重试" : state.ToString(), FailureKind: state == DownloadState.Failed ? BiliErrorKind.RequestRestricted : null)));
        Empty.Visibility = Visibility.Collapsed;
    }
    public QueuePage() { InitializeComponent(); Tasks.ItemsSource = rows; Loaded += (_, _) => { App.Services.Queue.Changed += Refresh; Refresh(); }; Unloaded += (_, _) => App.Services.Queue.Changed -= Refresh; }
    protected override void OnNavigatedTo(NavigationEventArgs e) { completed = e.Parameter as string == "completed"; PageTitle.Text = completed ? "已完成" : "下载队列"; }
    private void Refresh() => DispatcherQueue.TryEnqueue(() => {
        if (smoke) return;
        var items = App.Services.Queue.Snapshot().Where(t => !completed || t.State == DownloadState.Completed).ToArray();
        foreach (var row in rows.Where(r => items.All(i => i.Id != r.Data.Id)).ToArray()) rows.Remove(row);
        for (var i = 0; i < items.Length; i++)
        {
            var row = rows.FirstOrDefault(r => r.Data.Id == items[i].Id);
            if (row == null) rows.Insert(i, new(items[i])); else row.Update(items[i]);
        }
        Empty.Visibility = items.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
    });
    private static DownloadTaskData Item(object sender) => ((DownloadTaskViewModel)((FrameworkElement)sender).Tag).Data;
    private async void Pause_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Paused);
    private async void Resume_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Queued);
    private async void Cancel_Click(object sender, RoutedEventArgs e) => await Change(sender, DownloadState.Canceled);
    private async Task Change(object sender, DownloadState state)
    {
        try { await App.Services.Queue.SetStateAsync(Item(sender).Id, state); }
        catch (Exception) { Show("无法更新任务状态，请重试。", InfoBarSeverity.Error); }
    }
    private async void Remove_Click(object sender, RoutedEventArgs e) {
        var item = Item(sender);
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = "移除任务记录？", Content = "进行中的下载会停止，并清理该任务的临时缓存。已下载的视频文件会保留。", PrimaryButtonText = "移除", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await DialogCoordinator.ShowAsync(dialog) != ContentDialogResult.Primary) return;
        try { await App.Services.Queue.RemoveAsync(item.Id); }
        catch { Show("记录移除失败，请重试。", InfoBarSeverity.Error); }
    }
    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        var result = await LoginFlow.ShowAsync(XamlRoot);
        if (result.Outcome == LoginOutcome.Success) Refresh();
        if (AuthRecovery.LoginMessage(result, forTask: true) is { } message) Show(message, result.Outcome == LoginOutcome.Success ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
    }
    private async void Copy_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(ExistingPath(sender));
            var data = new DataPackage { RequestedOperation = DataPackageOperation.Copy };
            data.SetStorageItems([file]); Clipboard.SetContent(data); Clipboard.Flush();
            Show("已复制视频文件，可在支持文件粘贴的聊天窗口中粘贴。", InfoBarSeverity.Success);
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
        catch (Exception) { Show("暂时无法打开视频，请确认下载完成且已安装播放器。", InfoBarSeverity.Warning); }
    }
    private static string ExistingPath(object sender)
    {
        var item = Item(sender);
        if (item.State != DownloadState.Completed || item.FilePath == null || !File.Exists(item.FilePath)) throw new BiliException("视频尚未下载完成，或文件已被移动、删除。");
        return item.FilePath;
    }
    private void Show(string text, InfoBarSeverity severity) { Notice.Message = text; Notice.Severity = severity; Notice.IsOpen = true; }
}
