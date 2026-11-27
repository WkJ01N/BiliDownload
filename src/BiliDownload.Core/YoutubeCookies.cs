namespace BiliDownload.Core;

public sealed record BrowserCookie(string Domain, string Path, bool Secure, bool HttpOnly, long ExpiresUnix, string Name, string Value);

public static class YoutubeCookies
{
    public const int MaximumBytes = 128 * 1024;
    private static readonly HashSet<string> YoutubeAuthKeys = new(StringComparer.Ordinal)
    {
        "SID", "SAPISID", "__Secure-1PSID", "__Secure-3PSID", "__Secure-3PAPISID"
    };

    public static string Filter(string input, long? now = null) => Filter(input, false, now);
    public static string FilterX(string input, long? now = null) => Filter(input, true, now);

    public static string FromBrowserCookies(IEnumerable<BrowserCookie> cookies, bool x, long? now = null)
    {
        var current = now ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var list = cookies.Where(c => !string.IsNullOrWhiteSpace(c.Name) && !string.IsNullOrEmpty(c.Value)).ToList();
        var lines = new List<string>(list.Count);
        var hasAuth = false;
        foreach (var c in list)
        {
            var rawDomain = (c.Domain ?? "").Trim();
            if (string.IsNullOrEmpty(rawDomain)) continue;
            var host = rawDomain.TrimStart('.').ToLowerInvariant();
            bool Match(string domain) => host == domain || host.EndsWith("." + domain, StringComparison.Ordinal);
            if (x ? !Match("x.com") && !Match("twitter.com") : !Match("youtube.com")) continue;
            if (c.ExpiresUnix > 0 && c.ExpiresUnix <= current) continue;
            if (x ? c.Name == "auth_token" : YoutubeAuthKeys.Contains(c.Name)) hasAuth = true;
            var includeSubdomains = rawDomain.StartsWith('.') ? "TRUE" : "FALSE";
            var path = string.IsNullOrEmpty(c.Path) || !c.Path.StartsWith('/') ? "/" : c.Path;
            var secure = c.Secure ? "TRUE" : "FALSE";
            var expires = c.ExpiresUnix > 0 ? c.ExpiresUnix : 0;
            var prefix = c.HttpOnly ? "#HttpOnly_" : "";
            lines.Add($"{prefix}{(includeSubdomains == "TRUE" ? "." + host : host)}\t{includeSubdomains}\t{path}\t{secure}\t{expires}\t{c.Name}\t{c.Value}");
        }
        if (!hasAuth)
            throw new BiliException(
                x ? "尚未检测到 X 登录状态，请先完成网页登录。" : "尚未检测到 YouTube 登录状态，请先完成网页登录。",
                x ? BiliErrorKind.XSession : BiliErrorKind.YoutubeSession);
        var joined = string.Join('\n', lines);
        return x ? FilterX(joined, current) : Filter(joined, current);
    }

    private static string Filter(string input, bool x, long? now)
    {
        var current = now ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var rows = new List<string>();
        foreach (var line in input.Split('\n'))
        {
            var row = line.TrimEnd('\r');
            var data = row.StartsWith("#HttpOnly_", StringComparison.Ordinal) ? row[10..] : row;
            var fields = data.Split('\t');
            if (fields.Length != 7 || fields.Any(f => f.Any(char.IsControl))) continue;
            var host = fields[0].TrimStart('.').ToLowerInvariant();
            bool Match(string domain) => host == domain || host.EndsWith("." + domain, StringComparison.Ordinal);
            if (x ? !Match("x.com") && !Match("twitter.com") : !Match("youtube.com")) continue;
            if (fields[1] is not ("TRUE" or "FALSE") || fields[3] is not ("TRUE" or "FALSE") ||
                !fields[2].StartsWith('/') || fields[5].Length == 0 || fields[6].Length == 0 ||
                !long.TryParse(fields[4], out var expires) || expires != 0 && expires <= current) continue;
            // Normalize host-only flags to the convention MozillaCookieJar accepts.
            fields[0] = fields[1] == "TRUE" ? "." + host : host;
            rows.Add(string.Join('\t', fields));
        }
        if (rows.Count == 0) throw new BiliException($"文件中没有有效的 {(x ? "X" : "YouTube")} 会话记录。");
        return "# Netscape HTTP Cookie File\n" + string.Join('\n', rows) + "\n";
    }
}
