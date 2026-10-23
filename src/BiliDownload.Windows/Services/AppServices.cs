using BiliDownload.Core;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BiliDownload_Windows.Services;

public sealed class AppServices : IAsyncDisposable
{
    public string DataDirectory { get; }
    public BiliClient Bili { get; } = new();
    public AppSettings Settings { get; private set; }
    public DownloadQueue Queue { get; }
    public AppServices(string? dataDirectory = null)
    {
        DataDirectory = dataDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BiliDownload");
        Directory.CreateDirectory(DataDirectory);
        Settings = new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "BiliDownload"), FindFfmpeg());
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
        Queue = new(Bili, new TaskStore(DataDirectory), Path.Combine(DataDirectory, "cache"), () => ToolPathResolver.Resolve(Settings.FfmpegPath, AppContext.BaseDirectory));
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
    public async ValueTask DisposeAsync() { await Queue.DisposeAsync(); Bili.Dispose(); }
}
