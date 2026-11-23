using BiliDownload.Core;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BiliDownload_Windows.Services;

public sealed class AppServices : IAsyncDisposable
{
    public string DataDirectory { get; }
    public BiliClient Bili { get; }
    public MediaRouter Media { get; }
    public AppSettings Settings { get; private set; }
    public bool ClipboardMigrationNotice { get; }
    public ClipboardRequestStore ClipboardRequests { get; }
    public NotificationService Notifications { get; } = new();
    public DownloadQueue Queue { get; }
    private readonly HttpClient componentHttp = new(new HttpClientHandler { UseCookies = false }) { Timeout = TimeSpan.FromMinutes(15) };
    public ComponentInstaller Components { get; }
    public IReadOnlyList<ComponentPackage> ComponentPackages { get; private set; }
    private ComponentPackage? activeYoutube;
    private string ActiveYoutubeFile => Path.Combine(DataDirectory, "youtube-component.json");
    public string ResolveFfmpeg() => ToolPathResolver.Resolve(Settings.FfmpegPath, AppContext.BaseDirectory,
        ComponentPackages.Where(c => c.EntryPoint == "ffmpeg.exe").Select(Components.InstalledPath).FirstOrDefault(p => p != null));
    public AppServices(string? dataDirectory = null)
    {
        DataDirectory = dataDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BiliDownload");
        Directory.CreateDirectory(DataDirectory);
        Bili = App.IsAutomationTest ? new(new AutomationHttpHandler()) : new();
        Settings = new(App.IsAutomationTest ? Path.Combine(DataDirectory, "output") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "BiliDownload"), FindFfmpeg());
        try
        {
            if (File.Exists(SettingsFile)) Settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsFile)) ?? Settings;
            var credentials = Path.Combine(DataDirectory, "session.dat");
            if (File.Exists(credentials))
            {
                var value = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(credentials), null, DataProtectionScope.CurrentUser));
                Bili.Cookies.SetCookies(new Uri("https://www.bilibili.com"), value);
                Bili.RestoreSession();
            }
        }
        catch (Exception ex) when (ex is JsonException or CryptographicException or CookieException or IOException) { /* Recover using defaults or a fresh login. */ }
        ClipboardMigrationNotice = File.Exists(SettingsFile) && Settings.ClipboardBehaviorVersion < 1;
        SaveSettings(ClipboardPolicy.Migrate(Settings));
        ClipboardRequests = new(DataDirectory);
        Components = new(Path.Combine(DataDirectory, "components"), componentHttp);
        ComponentPackages = JsonSerializer.Deserialize<ComponentPackage[]>(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Assets", "components.json"))) ?? [];
        try {
            if (File.Exists(ActiveYoutubeFile) && JsonSerializer.Deserialize<ComponentPackage>(File.ReadAllText(ActiveYoutubeFile)) is { } saved &&
                TrustedYoutube(saved) && Components.InstalledPath(saved) != null) {
                activeYoutube = saved;
                if (IsNewer(saved, ComponentPackages.Single(p => p.Id == "youtube"))) OfferYoutube(saved);
            }
        } catch (Exception ex) when (ex is JsonException or IOException) { }
        activeYoutube ??= ComponentPackages.FirstOrDefault(p => p.Id == "youtube" && Components.InstalledPath(p) != null);
        var youtube = new YoutubeEngine(() => activeYoutube == null ? null : Components.InstalledPath(activeYoutube),
            Path.Combine(AppContext.BaseDirectory, "Assets", "youtube_bridge.py"), ReadYoutubeCookies);
        Media = new(Bili, youtube, () => Settings.ExpandRelatedItems);
        Queue = new(Media, new TaskStore(DataDirectory), Path.Combine(DataDirectory, "cache"), ResolveFfmpeg);
    }
    private static Version? EngineVersion(ComponentPackage p) => Version.TryParse(p.Version?.Replace('-', '.'), out var value) ? value : null;
    private static bool IsNewer(ComponentPackage a, ComponentPackage b) => EngineVersion(a) > EngineVersion(b);
    private static bool TrustedYoutube(ComponentPackage package) => package is { Id: "youtube", EntryPoint: "python.exe", DownloadBytes: > 0, InstalledBytes: > 0 } &&
        EngineVersion(package) != null && System.Text.RegularExpressions.Regex.IsMatch(package.Sha256 ?? "", "^[a-fA-F0-9]{64}$") &&
        System.Text.RegularExpressions.Regex.IsMatch(package.Url ?? "", @"^https://github\.com/WkJ01N/BiliDownload/releases/download/v[0-9.]+/youtube-win-x64\.zip$");
    private void OfferYoutube(ComponentPackage package) => ComponentPackages = ComponentPackages.Where(p => p.Id != "youtube").Append(package).ToArray();
    public async Task<bool> CheckYoutubeUpdateAsync(CancellationToken ct)
    {
        using var response = await componentHttp.GetAsync("https://github.com/WkJ01N/BiliDownload/releases/latest/download/youtube-component.json", HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        await response.Content.LoadIntoBufferAsync(32768, ct);
        var candidate = JsonSerializer.Deserialize<ComponentPackage>(await response.Content.ReadAsStringAsync(ct));
        if (candidate == null || !TrustedYoutube(candidate)) throw new BiliException("YouTube 组件更新清单无效。");
        if (!IsNewer(candidate, ComponentPackages.Single(p => p.Id == "youtube"))) return false;
        OfferYoutube(candidate); return true;
    }
    public void ActivateYoutube(ComponentPackage package)
    {
        File.WriteAllText(ActiveYoutubeFile + ".tmp", JsonSerializer.Serialize(package));
        File.Move(ActiveYoutubeFile + ".tmp", ActiveYoutubeFile, true);
        var old = activeYoutube; activeYoutube = package;
        // An old engine can still be finishing a download. Its content-addressed directory is safe to keep.
        if (old != null && old.Sha256 != package.Sha256)
            try { Components.Remove(old); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
    public void RemoveYoutube(ComponentPackage package)
    {
        Components.Remove(package);
        if (activeYoutube != null && activeYoutube.Sha256 != package.Sha256) Components.Remove(activeYoutube);
        File.Delete(ActiveYoutubeFile); activeYoutube = null;
    }
    private string YoutubeSessionFile => Path.Combine(DataDirectory, "youtube-session.dat");
    public bool HasYoutubeSession => File.Exists(YoutubeSessionFile);
    private string? ReadYoutubeCookies()
    {
        try { return HasYoutubeSession ? Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(YoutubeSessionFile), null, DataProtectionScope.CurrentUser)) : null; }
        catch (CryptographicException) { throw new BiliException("YouTube 会话无法解密，请重新导入。", BiliErrorKind.YoutubeSession); }
    }
    public void ImportYoutubeSession(string path)
    {
        if (new FileInfo(path).Length > YoutubeCookies.MaximumBytes) throw new BiliException("会话文件超过 128 KiB，请仅导出 YouTube 记录。");
        var text = YoutubeCookies.Filter(File.ReadAllText(path));
        File.WriteAllBytes(YoutubeSessionFile + ".tmp", ProtectedData.Protect(Encoding.UTF8.GetBytes(text), null, DataProtectionScope.CurrentUser));
        File.Move(YoutubeSessionFile + ".tmp", YoutubeSessionFile, true);
    }
    public void ClearYoutubeSession() => File.Delete(YoutubeSessionFile);
    private string SettingsFile => Path.Combine(DataDirectory, "settings.json");
    public void SaveSettings(AppSettings settings)
    {
        Settings = settings;
        var temp = SettingsFile + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(settings)); File.Move(temp, SettingsFile, true);
    }
    public void SaveSession()
    {
        var cookies = Bili.Cookies.GetCookies(new Uri("https://www.bilibili.com/")).Cast<Cookie>()
            .Select(c => $"{c.Name}={c.Value}; Domain=.bilibili.com; Path=/");
        var value = string.Join(", ", cookies);
        File.WriteAllBytes(Path.Combine(DataDirectory, "session.dat"), ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
        Bili.ConfirmLogin();
    }
    public void Logout()
    {
        foreach (Cookie cookie in Bili.Cookies.GetAllCookies()) cookie.Expired = true;
        File.Delete(Path.Combine(DataDirectory, "session.dat"));
        Bili.ClearSession();
    }
    private static string FindFfmpeg()
    {
        var bundled = Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");
        return File.Exists(bundled) ? bundled : "ffmpeg";
    }
    public async ValueTask DisposeAsync() { Notifications.Dispose(); await Queue.DisposeAsync(); Bili.Dispose(); componentHttp.Dispose(); }
}
