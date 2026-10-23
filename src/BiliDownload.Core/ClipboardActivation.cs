namespace BiliDownload.Core;

public static class ClipboardActivation
{
    public static string Create(string scheme, Guid id) => $"{scheme}://clipboard/{id:N}";
    public static bool TryParse(string value, string scheme, out Guid id)
    {
        id = default;
        return Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme.Equals(scheme, StringComparison.OrdinalIgnoreCase) &&
            uri.Host == "clipboard" && uri.IsDefaultPort && uri.UserInfo.Length == 0 && uri.Query.Length == 0 && uri.Fragment.Length == 0 &&
            Guid.TryParseExact(uri.AbsolutePath.TrimStart('/'), "N", out id);
    }
}
