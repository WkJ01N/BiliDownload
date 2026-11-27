using BiliDownload.Core;
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

namespace BiliDownload_Windows.ViewModels;

public sealed class DownloadTaskViewModel(DownloadTaskData data) : INotifyPropertyChanged
{
    private bool isSelected;
    private bool selectionMode;

    public DownloadTaskData Data { get; private set; } = data;
    public MediaItem Item => Data.Item;
    public string PartTitle => Item.PartTitle == Item.Title ? "" : Item.PartTitle;
    public Visibility PartTitleVisibility =>
        App.Services.Settings.EffectiveListDensity != "Compact" && !string.IsNullOrWhiteSpace(PartTitle)
            ? Visibility.Visible
            : Visibility.Collapsed;
    public double Progress => Data.Progress;
    public string ProgressText => $"{Math.Clamp((int)Math.Round(Data.Progress), 0, 100)}%";
    public string Message => Data.Message;
    public string FileName => Data.FinalFileName ?? Data.OutputFileName ?? (Data.FilePath != null ? Path.GetFileName(Data.FilePath) : "");
    public Visibility FileNameVisibility => string.IsNullOrWhiteSpace(FileName) ? Visibility.Collapsed : Visibility.Visible;
    public string PlatformLabel => Item.X != null ? "X" : Item.Music ? "YT Music" : Item.YoutubeId != null ? "YouTube" : "Bilibili";
    public string StateLabel => Data.State switch
    {
        DownloadState.Queued => "等待中",
        DownloadState.Downloading => "下载中",
        DownloadState.Merging => "合并中",
        DownloadState.Paused => "已暂停",
        DownloadState.Completed => "已完成",
        DownloadState.Failed => "失败",
        DownloadState.Canceled => "已取消",
        _ => Data.State.ToString()
    };

    public bool IsSelected
    {
        get => isSelected;
        set
        {
            if (isSelected == value) return;
            isSelected = value;
            PropertyChanged?.Invoke(this, new(nameof(IsSelected)));
            SelectionChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    public event EventHandler? SelectionChanged;

    public Visibility SelectionCheckboxVisibility => selectionMode && Data.State == DownloadState.Completed
        ? Visibility.Visible
        : Visibility.Collapsed;

    public void SetSelectionMode(bool enabled)
    {
        if (selectionMode == enabled && (!enabled || !isSelected)) return;
        selectionMode = enabled;
        if (!enabled) isSelected = false;
        PropertyChanged?.Invoke(this, new(nameof(SelectionCheckboxVisibility)));
        PropertyChanged?.Invoke(this, new(nameof(IsSelected)));
    }

    public Thickness CardPadding => App.Services.Settings.EffectiveListDensity switch
    {
        "Compact" => new Thickness(12, 8, 12, 8),
        "Comfortable" => new Thickness(18, 14, 18, 14),
        _ => new Thickness(14, 10, 14, 10)
    };

    public double CoverWidth => App.Services.Settings.EffectiveListDensity switch
    {
        "Compact" => 68,
        "Comfortable" => 100,
        _ => 80
    };

    public double CoverHeight => App.Services.Settings.EffectiveListDensity switch
    {
        "Compact" => 42,
        "Comfortable" => 62,
        _ => 50
    };

    public double CardSpacing => App.Services.Settings.EffectiveListDensity switch
    {
        "Compact" => 5,
        "Comfortable" => 10,
        _ => 7
    };

    public int TitleMaxLines => App.Services.Settings.EffectiveListDensity switch
    {
        "Compact" => 1,
        "Comfortable" => 3,
        _ => 2
    };

    public Visibility MessageVisibility => Data.State == DownloadState.Completed ? Visibility.Collapsed : Visibility.Visible;
    public Visibility DividerVisibility => App.Services.Settings.EffectiveListDensity == "Compact" ? Visibility.Collapsed : Visibility.Visible;

    public BitmapImage? CoverImage
    {
        get
        {
            if (string.IsNullOrWhiteSpace(Item.Cover)) return null;
            var normalized = Item.Cover.StartsWith("//", StringComparison.Ordinal) ? "https:" + Item.Cover : Item.Cover;
            return Uri.TryCreate(normalized, UriKind.Absolute, out var uri) ? new BitmapImage(uri) : null;
        }
    }

    public Brush StateBadgeBackground => ResolveBrush(Data.State switch
    {
        DownloadState.Completed => "SystemFillColorSuccessBackgroundBrush",
        DownloadState.Failed => "SystemFillColorCriticalBackgroundBrush",
        DownloadState.Paused => "SystemFillColorCautionBackgroundBrush",
        DownloadState.Downloading or DownloadState.Merging => "SystemFillColorAttentionBackgroundBrush",
        _ => "SystemFillColorNeutralBackgroundBrush"
    });

    public Brush StateBadgeForeground => ResolveBrush(Data.State switch
    {
        DownloadState.Completed => "SystemFillColorSuccessBrush",
        DownloadState.Failed => "SystemFillColorCriticalBrush",
        DownloadState.Paused => "SystemFillColorCautionBrush",
        DownloadState.Downloading or DownloadState.Merging => "SystemFillColorAttentionBrush",
        _ => "SystemFillColorNeutralBrush"
    });

    public Brush MessageForeground => ResolveBrush(Data.State == DownloadState.Failed
        ? "SystemFillColorCriticalBrush"
        : "RadixSecondaryTextBrush");

    public Visibility ProgressVisibility => Data.State == DownloadState.Completed ? Visibility.Collapsed : Visibility.Visible;

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
    public string EngineSettingsLabel => Data.FailureKind == BiliErrorKind.XSession ? "登录 X" : Data.FailureKind == BiliErrorKind.YoutubeSession ? "登录 YouTube" : Data.FailureKind == BiliErrorKind.XComponent ? "下载组件" : "YouTube 设置";
    public Visibility YoutubeVisibility => Data.State == DownloadState.Failed && Data.FailureKind is BiliErrorKind.YoutubeComponent or BiliErrorKind.YoutubeSession or BiliErrorKind.XComponent or BiliErrorKind.XSession ? Visibility.Visible : Visibility.Collapsed;
    public Visibility AudioComponentVisibility => Data.State == DownloadState.Failed && Data.FailureKind == BiliErrorKind.AudioComponent ? Visibility.Visible : Visibility.Collapsed;
    public Visibility TrackChangedVisibility => Data.State == DownloadState.Failed && Data.FailureKind == BiliErrorKind.TrackChanged ? Visibility.Visible : Visibility.Collapsed;
    public Visibility LoginVisibility => Data.State == DownloadState.Failed && AuthRecovery.NeedsLogin(Data.FailureKind, App.Services.Bili.SessionState) ? Visibility.Visible : Visibility.Collapsed;

    public event PropertyChangedEventHandler? PropertyChanged;

    public void Update(DownloadTaskData value)
    {
        Data = value;
        PropertyChanged?.Invoke(this, new(null));
    }

    public void RefreshTheme() => PropertyChanged?.Invoke(this, new(null));
    public void RefreshDensity() => PropertyChanged?.Invoke(this, new(null));

    private static Brush ResolveBrush(string key)
    {
        var radix = Application.Current.Resources.MergedDictionaries
            .FirstOrDefault(d => d.Source?.OriginalString.EndsWith("RadixTheme.xaml", StringComparison.OrdinalIgnoreCase) == true);
        if (radix != null)
        {
            var isLight = App.Main?.Content is FrameworkElement fe &&
                (fe.RequestedTheme == ElementTheme.Light || (fe.RequestedTheme == ElementTheme.Default && fe.ActualTheme == ElementTheme.Light));
            var themeKey = isLight ? "Light" : "Default";
            if (radix.ThemeDictionaries.TryGetValue(themeKey, out var dictObj) && dictObj is ResourceDictionary dict && dict.TryGetValue(key, out var themed) && themed is Brush b)
                return b;
        }
        return new SolidColorBrush(Microsoft.UI.Colors.Gray);
    }
}
