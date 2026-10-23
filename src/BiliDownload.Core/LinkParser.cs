using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public static partial class LinkParser
{
    [GeneratedRegex(@"https?://(?:[a-z0-9-]+\.)*(?:bilibili\.com|b23\.tv)(?::\d+)?(?=[/?#\s<>""，。；！？、）】]|$)(?:[/?#][^\s<>""，。；！？、）】]*)?", RegexOptions.IgnoreCase)]
    private static partial Regex Links();
    [GeneratedRegex(@"(?<![a-zA-Z0-9])(?:BV[0-9a-zA-Z]{10}|av\d+)(?![a-zA-Z0-9])", RegexOptions.IgnoreCase)]
    private static partial Regex VideoIds();

    public static IReadOnlyList<Uri> Extract(string input)
    {
        var links = Links().Matches(input).Select(m => m.Value.TrimEnd('.', ',', ';', ')', ']', '!', '?'))
            .Select(value => new Uri(value)).Where(IsAllowed).ToList();
        if (links.Count == 0 && !input.Contains("://", StringComparison.Ordinal))
            links.AddRange(VideoIds().Matches(input).Select(m => new Uri("https://www.bilibili.com/video/" + m.Value)));
        return links.DistinctBy(u => u.AbsoluteUri).ToArray();
    }

    public static bool IsAllowed(Uri uri) => uri.Scheme is "http" or "https" && uri.IsDefaultPort &&
        (uri.Host.Equals("b23.tv", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.Equals("bilibili.com", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.EndsWith(".bilibili.com", StringComparison.OrdinalIgnoreCase));

    public static string SafeName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars().Concat("<>:\"/\\|?*").ToHashSet();
        var safe = new string(name.Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray()).Trim().TrimEnd('.');
        if (safe.Length > 100) safe = safe[..100];
        return string.IsNullOrWhiteSpace(safe) ? "video" : safe;
    }
}
