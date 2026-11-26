using BiliDownload.Core;
using System.ComponentModel;
using Microsoft.UI.Xaml;

namespace BiliDownload_Windows.ViewModels;

public sealed class DownloadTaskViewModel(DownloadTaskData data) : INotifyPropertyChanged
{
    public DownloadTaskData Data { get; private set; } = data;
    public MediaItem Item => Data.Item;
    public string PartTitle => Item.PartTitle == Item.Title ? "" : Item.PartTitle;
    public double Progress => Data.Progress;
    public string Message => Data.Message;
    public string FileName => Data.FinalFileName ?? Data.OutputFileName ?? (Data.FilePath != null ? Path.GetFileName(Data.FilePath) : "");
    public bool CanPause => Data.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging;
    public bool CanResume => Data.State is DownloadState.Paused or DownloadState.Failed;
    public bool CanCancel => Data.State is not (DownloadState.Completed or DownloadState.Canceled);
    public bool CanUseFile => Data.State == DownloadState.Completed;
    public Visibility PauseVisibility => CanPause ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ResumeVisibility => CanResume ? Visibility.Visible : Visibility.Collapsed;
    public Visibility CancelVisibility => CanCancel ? Visibility.Visible : Visibility.Collapsed;
    public Visibility FileVisibility => CanUseFile ? Visibility.Visible : Visibility.Collapsed;
    public string OpenLabel => Item.X?.Kind is "image" or "markdown" || Item.X?.ExportGif == true ? "打开文件" : "播放";
    public string ReparseLabel => Item.X == null || Data.AudioOutput != null ? "重选音轨" : "重新解析";
    public string EngineSettingsLabel => Data.FailureKind == BiliErrorKind.XSession ? "X 会话" : Data.FailureKind == BiliErrorKind.XComponent ? "下载组件" : "YouTube 设置";
    public Visibility YoutubeVisibility => Data.State == DownloadState.Failed && Data.FailureKind is BiliErrorKind.YoutubeComponent or BiliErrorKind.YoutubeSession or BiliErrorKind.XComponent or BiliErrorKind.XSession ? Visibility.Visible : Visibility.Collapsed;
    public Visibility AudioComponentVisibility => Data.State == DownloadState.Failed && Data.FailureKind == BiliErrorKind.AudioComponent ? Visibility.Visible : Visibility.Collapsed;
    public Visibility TrackChangedVisibility => Data.State == DownloadState.Failed && Data.FailureKind == BiliErrorKind.TrackChanged ? Visibility.Visible : Visibility.Collapsed;
    public Visibility LoginVisibility => Data.State == DownloadState.Failed && AuthRecovery.NeedsLogin(Data.FailureKind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
    public event PropertyChangedEventHandler? PropertyChanged;
    public void Update(DownloadTaskData value) { Data = value; PropertyChanged?.Invoke(this, new(null)); }
}
