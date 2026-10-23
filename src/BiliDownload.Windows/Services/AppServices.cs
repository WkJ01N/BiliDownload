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
    public AppSettings Settings { get; private set; }
    public bool ClipboardMigrationNotice { get; }
    public ClipboardRequestStore ClipboardRequests { get; }
    public NotificationService Notifications { get; } = new();
    public DownloadQueue Queue { get; }
    private readonly HttpClient componentHttp = new(new HttpClientHandler { UseCookies = false }) { Timeout = TimeSpan.FromMinutes(15) };
    public ComponentInstaller Components { get; }
    public IReadOnlyList<ComponentPackage> ComponentPackages { get; }
    public string ResolveFfmpeg() => ToolPathResolver.Resolve(Settings.FfmpegPath, AppContext.BaseDirectory,
        ComponentPackages.Select(Components.InstalledPath).FirstOrDefault(p => p != null));
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
        Queue = new(Bili, new TaskStore(DataDirectory), Path.Combine(DataDirectory, "cache"), ResolveFfmpeg);
    }
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
