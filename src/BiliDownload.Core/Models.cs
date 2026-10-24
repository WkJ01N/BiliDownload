namespace BiliDownload.Core;

public enum MediaKind { Video, Bangumi, Course }
public record MediaItem(string Id, string Title, string PartTitle, string Cover, MediaKind Kind,
    long Aid, string Bvid, long Cid, long EpisodeId, int Index, string PageUrl, int PartCount = 1);
public record MediaCatalog(string Title, IReadOnlyList<MediaItem> Items, string? TargetItemId = null);
public record MediaVariant(int Quality, string Label, int Height, string Codec, long? EstimatedBytes = null) {
    public string DisplayLabel => Label + "  " + MediaSize.Format(EstimatedBytes);
}
public record StreamPart(string Key, string Url, string[] Backups);
public record DownloadPlan(MediaVariant Variant, IReadOnlyList<StreamPart> Video, StreamPart? Audio);
public enum DownloadState { Queued, Downloading, Merging, Paused, Completed, Failed, Canceled }
public record DownloadTaskData(Guid Id, MediaItem Item, int Quality, string Directory,
    DownloadState State = DownloadState.Queued, double Progress = 0, string Message = "等待下载",
    string? FilePath = null, DateTimeOffset CreatedAt = default, BiliErrorKind? FailureKind = null, string? OutputFileName = null, string? FinalFileName = null);
public record AppSettings(string DownloadDirectory, string FfmpegPath = "ffmpeg",
    bool DetectOnActivate = false, bool MonitorClipboard = true, string Theme = "Default",
    bool AutoDownloadOnNotificationClick = true, bool ExpandRelatedItems = false, int ClipboardBehaviorVersion = 0, string FileNameTemplate = "{name}", string CloseBehavior = "Ask");
public enum BiliErrorKind { Unknown, LoginRequired, RequestRestricted }
public enum SessionState { Guest, Saved, Authenticated, Expired }
public enum LoginOutcome { Success, Canceled, Failed }
public record LoginResult(LoginOutcome Outcome, string? Message = null);
public class BiliException(string message, BiliErrorKind kind = BiliErrorKind.Unknown,
    int? httpStatus = null, long? apiCode = null) : Exception(message)
{
    public BiliErrorKind Kind { get; } = kind;
    public int? HttpStatus { get; } = httpStatus;
    public long? ApiCode { get; } = apiCode;
}

public static class AuthRecovery
{
    public static string? LoginMessage(LoginResult result, bool forTask = false) => result.Outcome switch
    {
        LoginOutcome.Success when forTask => "已登录，请点击“继续 / 重试”。",
        LoginOutcome.Success => "已登录，请重新解析。",
        LoginOutcome.Failed => result.Message ?? "登录失败，请重试。",
        _ => null
    };
    public static bool NeedsLogin(BiliErrorKind? kind, SessionState state) =>
        kind == BiliErrorKind.LoginRequired || kind == BiliErrorKind.RequestRestricted && state != SessionState.Authenticated;
    public static string Message(BiliErrorKind kind, SessionState state) => kind switch
    {
        BiliErrorKind.LoginRequired => "登录信息已失效或此操作需要登录，请登录 B 站后重试。",
        BiliErrorKind.RequestRestricted when state == SessionState.Authenticated => "B 站暂时限制了当前请求，请稍后重试。重新登录不一定能解除限制。",
        BiliErrorKind.RequestRestricted => "B 站暂时限制了当前请求。你可以先登录 B 站再重试，登录不保证解除所有请求限制。",
        _ => "请求失败，请稍后重试。"
    };
}

public interface IMediaService
{
    Task<IReadOnlyList<MediaCatalog>> ResolveAsync(string input, CancellationToken ct = default);
    Task<IReadOnlyList<MediaVariant>> GetVariantsAsync(MediaItem item, CancellationToken ct = default);
    Task<DownloadPlan> GetPlanAsync(MediaItem item, int quality, CancellationToken ct = default);
}
