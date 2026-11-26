using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public sealed record XLink(string PostId, string? Selector = null, int? Index = null)
{
    public string Canonical => "https://x.com/i/status/" + PostId + (Index is null ? "" : $"/{Selector}/{Index}");
    private static readonly Regex PathPattern = new(@"^/(?:i/(?:web/)?status|[A-Za-z0-9_]{1,15}/status(?:es)?)/([1-9][0-9]{0,19})(?:/(photo|video)/([1-4]))?/?\z", RegexOptions.CultureInvariant);
    private static bool Valid(Uri u) => u.IsAbsoluteUri && u.Scheme is "http" or "https" && u.IsDefaultPort && u.UserInfo == "";
    public static XLink? Parse(Uri uri)
    {
        if (!Valid(uri) || uri.IdnHost is not ("x.com" or "www.x.com" or "mobile.x.com" or "m.x.com" or "twitter.com" or "www.twitter.com" or "mobile.twitter.com" or "m.twitter.com")) return null;
        var match = PathPattern.Match(uri.AbsolutePath);
        if (!match.Success) return null;
        return new(match.Groups[1].Value, match.Groups[2].Success ? match.Groups[2].Value : null,
            match.Groups[3].Success ? int.Parse(match.Groups[3].Value) : null);
    }
    public static bool IsShort(Uri uri) => Valid(uri) && uri.IdnHost == "t.co" && Regex.IsMatch(uri.AbsolutePath, @"^/[A-Za-z0-9]{1,64}/?\z");
    private static readonly HttpClient Redirects = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false });
    public static async Task<XLink> ExpandAsync(Uri uri, CancellationToken ct, HttpClient? http = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        if (IsShort(uri)) uri = new Uri("https://t.co" + uri.AbsolutePath);
        var seen = new HashSet<string>();
        try {
            for (var hop = 0; hop < 5 && IsShort(uri) && seen.Add(uri.AbsoluteUri); hop++) {
                using var request = new HttpRequestMessage(HttpMethod.Get, uri);
                using var response = await (http ?? Redirects).SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
                if ((int)response.StatusCode is not (301 or 302 or 303 or 307 or 308) || response.Headers.Location is not {} location) break;
                if (!Uri.TryCreate(uri, location, out var next) || next.Scheme != "https") break;
                if (Parse(next) is {} result) return result;
                uri = next;
            }
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new BiliException("X 短链接跳转超时，请重试。"); }
        throw new BiliException("此短链接未指向支持的 X 帖子。");
    }
}

public record XAttachment(string PostId, string Handle, string Key, string Kind, string Extension,
    long? Bytes = null, MediaVariant[]? Variants = null, bool HasAudio = false, double Duration = 0,
    string? Markdown = null, string[]? Related = null, bool ExportGif = false, bool Truncated = false,
    string? Author = null, string? Text = null)
{
    public bool Available => Kind is "image" or "markdown" || Variants is { Length: > 0 };
    public string OutputExtension => ExportGif ? ".gif" : Extension;
    public string DirectoryUnder(string root, bool byType, bool audio) => Path.Combine(root,
        byType ? audio ? "音频" : Kind switch { "image" => "图片", "markdown" => "正文", _ => "视频" }
               : FileNaming.Normalize(Handle + "-" + PostId, ""));
}

public static class XPost
{
    public static MediaCatalog Read(JsonElement post, XLink link)
    {
        var id = post.Str("id");
        if (!Regex.IsMatch(id, @"^[1-9][0-9]{0,19}\z")) throw new BiliException("X 帖子编号无效。");
        var handle = post.Str("handle").TrimStart('@');
        if (!Regex.IsMatch(handle, @"^[A-Za-z0-9_]{1,15}\z")) handle = "X";
        var title = $"@{handle} {id}";
        var author = post.Str("author"); var text = post.Str("text"); var date = post.Str("createdAt");
        var url = $"https://x.com/{handle}/status/{id}";
        var items = new List<MediaItem>();
        foreach (var row in post.GetProperty("items").EnumerateArray()) {
            var key = row.Str("id"); int index = (int)row.Num("index");
            string kind = row.Str("kind"), ext = row.Str("extension");
            if (key.Length == 0 || index <= 0 || kind switch { "image" => ext is not (".jpg" or ".jpeg" or ".png" or ".webp"), "gif" or "video" => ext != ".mp4", _ => true }) continue;
            var variants = row.TryGetProperty("variants", out var list) ? list.EnumerateArray().Select(v => new MediaVariant(
                (int)v.Num("height"), v.Num("height") + "p", (int)v.Num("height"), "MP4", v.Num("bytes") is > 0 and var n ? n : null, v.Str("format"))).ToArray() : [];
            var media = new XAttachment(id, handle, key, kind, ext, row.Num("bytes") is > 0 and var bytes ? bytes : null, variants,
                row.TryGetProperty("audio", out var a) && a.ValueKind == JsonValueKind.True, row.TryGetProperty("duration",out var d) && d.TryGetDouble(out var duration) ? duration : 0);
            string label = kind switch { "image" => "图片", "gif" => "动图", _ => "视频" };
            items.Add(new($"x:{id}:{key}", title, $"{label} {index}", row.Str("thumbnail"), MediaKind.Video, 0,"",0,0,index,url,X:media));
        }
        var markdown = $"# {title}\n\n{author} (@{handle})\n\n{date}\n\n{url}\n\n{text}\n";
        var truncated = post.TryGetProperty("truncated",out var flag) && flag.ValueKind == JsonValueKind.True;
        if (truncated) markdown += "\n（来源仅提供部分正文）\n";
        if (Uri.TryCreate(post.Str("quotedUrl"), UriKind.Absolute, out var quoted) && XLink.Parse(quoted) != null) markdown += $"\n引用：{quoted.GetLeftPart(UriPartial.Path)}\n";
        var doc = new MediaItem($"x:{id}:markdown", title, "正文", "", MediaKind.Video, 0,"",0,0,items.Count+1,url,
            X:new(id,handle,"markdown","markdown",".md",Markdown:markdown,Truncated:truncated,Author:author,Text:text));
        var target = link.Index is {} i ? items.FirstOrDefault(r => r.Index == i && (link.Selector == "photo" ? r.X!.Kind == "image" : r.X!.Kind is "video" or "gif"))?.Id
            ?? throw new BiliException("帖子中没有指定的附件。") : items.Count switch { 0 => doc.Id, 1 => items[0].Id, _ => null };
        items.Add(doc);
        return new(title,items,target);
    }
}
