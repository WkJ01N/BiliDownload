using Microsoft.Win32;
using System.Security.Cryptography;
using System.Text;

namespace BiliDownload_Windows.Services;

public static class NotificationProtocol
{
    public static string Scheme => App.IsAutomationTest ? "bilidownload-qa-" +
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(AppContext.BaseDirectory.ToUpperInvariant())))[..12].ToLowerInvariant() : "bilidownload";
    public static void Register()
    {
        using var key = Registry.CurrentUser.CreateSubKey("Software\\Classes\\" + Scheme);
        key.SetValue("", "URL:BiliDownload clipboard link"); key.SetValue("URL Protocol", "");
        using var command = key.CreateSubKey("shell\\open\\command");
        command.SetValue("", $"\"{Environment.ProcessPath}\" \"%1\"");
    }
    public static void Unregister()
    {
        try {
            using var command = Registry.CurrentUser.OpenSubKey("Software\\Classes\\" + Scheme + "\\shell\\open\\command");
            if (command?.GetValue("") is string value && value.StartsWith($"\"{Environment.ProcessPath}\"", StringComparison.OrdinalIgnoreCase))
                Registry.CurrentUser.DeleteSubKeyTree("Software\\Classes\\" + Scheme, false);
        } catch (Exception) { }
    }
}
