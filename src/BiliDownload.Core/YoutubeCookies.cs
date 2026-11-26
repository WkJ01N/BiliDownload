namespace BiliDownload.Core;

public static class YoutubeCookies
{
    public const int MaximumBytes = 128 * 1024;
    public static string Filter(string input, long? now = null) => Filter(input, false, now);
    public static string FilterX(string input, long? now = null) => Filter(input, true, now);
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
