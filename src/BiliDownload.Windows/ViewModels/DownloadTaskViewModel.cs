using BiliDownload.Core;
using System.ComponentModel;
using Microsoft.UI.Xaml;

namespace BiliDownload_Windows.ViewModels;

public sealed class DownloadTaskViewModel(DownloadTaskData data) : INotifyPropertyChanged
{
    public DownloadTaskData Data { get; private set; } = data;
    public MediaItem Item => Data.Item;
    public double Progress => Data.Progress;
    public string Message => Data.Message;
    public bool CanPause => Data.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging;
    public bool CanResume => Data.State is DownloadState.Paused or DownloadState.Failed;
    public bool CanCancel => Data.State is not (DownloadState.Completed or DownloadState.Canceled);
    public bool CanUseFile => Data.State == DownloadState.Completed;
    public Visibility PauseVisibility => CanPause ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ResumeVisibility => CanResume ? Visibility.Visible : Visibility.Collapsed;
    public Visibility CancelVisibility => CanCancel ? Visibility.Visible : Visibility.Collapsed;
    public Visibility FileVisibility => CanUseFile ? Visibility.Visible : Visibility.Collapsed;
    public Visibility LoginVisibility => Data.State == DownloadState.Failed && AuthRecovery.NeedsLogin(Data.FailureKind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;
    public event PropertyChangedEventHandler? PropertyChanged;
    public void Update(DownloadTaskData value) { Data = value; PropertyChanged?.Invoke(this, new(null)); }
}
