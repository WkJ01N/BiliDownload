using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public sealed partial class BiliClient : IMediaService, IDisposable
{
    public const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    public CookieContainer Cookies { get; } = new();
    public SessionState SessionState { get; private set; } = SessionState.Guest;
    public void RestoreSession() => SessionState = SessionState.Saved;
    public void ConfirmLogin() => SessionState = SessionState.Authenticated;
    public void ClearSession() => SessionState = SessionState.Guest;
    private readonly HttpClient http;
    public BiliClient(HttpMessageHandler? handler = null)
    {
        http = new HttpClient(handler ?? new HttpClientHandler { CookieContainer = Cookies, AllowAutoRedirect = false,
            AutomaticDecompression = DecompressionMethods.All }) { Timeout = TimeSpan.FromSeconds(30) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        http.DefaultRequestHeaders.Referrer = new Uri("https://www.bilibili.com/");
    }

    public async Task<JsonElement> ApiAsync(string url, CancellationToken ct = default)
    {
        using var response = await http.GetAsync(url, ct);
        if (!response.IsSuccessStatusCode)
        {
            var status = (int)response.StatusCode;
            var kind = status == 412 ? BiliErrorKind.RequestRestricted : status == 401 ? BiliErrorKind.LoginRequired : BiliErrorKind.Unknown;
            if (kind == BiliErrorKind.LoginRequired) SessionState = SessionState.Expired;
            throw new BiliException(kind == BiliErrorKind.Unknown ? $"B 站请求失败（HTTP {status}），请稍后重试。" : AuthRecovery.Message(kind, SessionState), kind, status);
        }
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
        var root = doc.RootElement;
        var code = root.Num("code");
        if (url.StartsWith("https://api.bilibili.com/x/web-interface/nav", StringComparison.Ordinal) && root.TryGetProperty("data", out var account) && account.ValueKind == JsonValueKind.Object)
            SessionState = account.Num("isLogin") != 0 ? SessionState.Authenticated : SessionState == SessionState.Guest ? SessionState.Guest : SessionState.Expired;
        var anonymousNav = url.StartsWith("https://api.bilibili.com/x/web-interface/nav", StringComparison.Ordinal) &&
            code == -101 && root.TryGetProperty("data", out var navData) && navData.ValueKind == JsonValueKind.Object && navData.TryGetProperty("wbi_img", out _);
        if (code != 0 && !anonymousNav)
        {
            var kind = code == -101 ? BiliErrorKind.LoginRequired : code is -412 or -352 or -509 ? BiliErrorKind.RequestRestricted : BiliErrorKind.Unknown;
            if (kind == BiliErrorKind.LoginRequired) SessionState = SessionState.Expired;
            throw new BiliException(code switch
            {
                -101 => AuthRecovery.Message(kind, SessionState),
                -404 or 62002 => "视频不存在或已被删除。",
                -412 or -352 or -509 => AuthRecovery.Message(kind, SessionState),
                _ => $"B 站返回错误 {code}：{root.Str("message", "暂时无法获取内容") }"
            }, kind, apiCode: code);
        }
        return (root.TryGetProperty("data", out var data) && data.ValueKind != JsonValueKind.Null ? data : root.GetProperty("result")).Clone();
    }

    public async Task<IReadOnlyList<MediaCatalog>> ResolveAsync(string input, CancellationToken ct = default)
    {
        var links = LinkParser.Extract(input);
        if (links.Count == 0) throw new BiliException("没有找到 B 站链接，请粘贴链接、BV 号或分享文案。");
        var catalogs = new List<MediaCatalog>();
        var seen = new HashSet<string>();
        foreach (var original in links)
        {
            var uri = await ExpandAsync(original, ct);
            var path = uri.AbsolutePath;
            MediaCatalog catalog;
            var video = VideoPath().Match(path);
            if (video.Success)
            {
                var id = video.Groups[1].Value;
                var data = await ApiAsync("https://api.bilibili.com/x/web-interface/view?" +
                    (id.StartsWith("av", StringComparison.OrdinalIgnoreCase) ? "aid=" + id[2..] : "bvid=" + id), ct);
                var title = data.Str("title"); var bvid = data.Str("bvid");
                catalog = new(title, data.GetProperty("pages").EnumerateArray().Select((p, i) =>
                    new MediaItem($"video:{p.Num("cid")}", title, p.Str("part"), data.Str("pic"), MediaKind.Video,
                        data.Num("aid"), bvid, p.Num("cid"), 0, i + 1, $"https://www.bilibili.com/video/{bvid}?p={i + 1}")).ToArray());
                var selectedPage = ParseQuery(uri).GetValueOrDefault("p");
                if (int.TryParse(selectedPage, out var page) && page > 0)
                    catalog = catalog with { Items = catalog.Items.OrderByDescending(p => p.Index == page).ToArray() };
            }
            else if (path.Contains("/bangumi/", StringComparison.OrdinalIgnoreCase) || path.Contains("/cheese/", StringComparison.OrdinalIgnoreCase))
            {
                var match = EpisodePath().Match(path);
                if (!match.Success) throw new BiliException("请使用包含 ep、ss 或 md 编号的剧集或课程链接。");
                var kind = path.Contains("/cheese/") ? MediaKind.Course : MediaKind.Bangumi;
                var prefix = match.Groups[1].Value; var id = match.Groups[2].Value;
                if (prefix == "md")
                {
                    var media = await ApiAsync("https://api.bilibili.com/pgc/review/user?media_id=" + id, ct);
                    id = media.GetProperty("media").Num("season_id").ToString(); prefix = "ss";
                }
                var endpoint = kind == MediaKind.Course ? "pugv/view/web/season" : "pgc/view/web/season";
                var data = await ApiAsync($"https://api.bilibili.com/{endpoint}?{(prefix == "ep" ? "ep_id" : "season_id")}={id}", ct);
                var title = data.Str("title"); var cover = data.Str("cover");
                var episodes = new List<JsonElement>();
                if (data.TryGetProperty("episodes", out var main)) episodes.AddRange(main.EnumerateArray());
                if (data.TryGetProperty("section", out var sections) && sections.ValueKind == JsonValueKind.Array)
                    foreach (var section in sections.EnumerateArray())
                        if (section.TryGetProperty("episodes", out var eps)) episodes.AddRange(eps.EnumerateArray());
                catalog = new(title, episodes.DistinctBy(e => e.Num("id")).Select((e, i) =>
                {
                    var ep = e.Num("id");
                    return new MediaItem($"{kind}:{ep}", title,
                        string.Join(" ", new[] { e.Str("title"), e.Str("long_title") }.Where(s => s.Length > 0)),
                        e.Str("cover", cover), kind, e.Num("aid"), e.Str("bvid"), e.Num("cid"), ep, i + 1,
                        $"https://www.bilibili.com/{(kind == MediaKind.Course ? "cheese" : "bangumi")}/play/ep{ep}");
                }).ToArray());
            }
            else if (uri.Host.StartsWith("live."))
                throw new BiliException("这是直播间链接。请提供已发布回放的视频链接；首版不录制正在直播的内容。");
            else throw new BiliException("暂不识别此链接。请提供视频、番剧分集或课程播放链接。");
            var items = catalog.Items.Where(i => seen.Add(i.Id)).ToArray();
            if (items.Length > 0) catalogs.Add(catalog with { Items = items });
        }
        if (catalogs.Count == 0) throw new BiliException("没有找到可下载条目。");
        return catalogs;
    }

    private async Task<Uri> ExpandAsync(Uri uri, CancellationToken ct)
    {
        for (var i = 0; i < 6; i++)
        {
            if (!LinkParser.IsAllowed(uri)) throw new BiliException("链接跳转到了不支持的网站。");
            if (!uri.Host.Equals("b23.tv", StringComparison.OrdinalIgnoreCase)) return uri;
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            if ((int)response.StatusCode is >= 300 and < 400 && response.Headers.Location is { } location)
                uri = location.IsAbsoluteUri ? location : new Uri(uri, location);
            else throw new BiliException("短链接未能跳转，请使用原始视频链接。");
        }
        throw new BiliException("短链接跳转次数过多。");
    }

    private async Task<JsonElement> PlayInfoAsync(MediaItem item, int quality, CancellationToken ct)
    {
        var parameters = new Dictionary<string, string> { ["cid"] = item.Cid.ToString(), ["qn"] = quality.ToString(),
            ["fnval"] = "4048", ["fnver"] = "0", ["fourk"] = "1" };
        string url;
        if (item.Kind == MediaKind.Video)
        {
            parameters["bvid"] = item.Bvid;
            var nav = await ApiAsync("https://api.bilibili.com/x/web-interface/nav", ct);
            var wbi = nav.GetProperty("wbi_img");
            static string Key(string url) => Path.GetFileNameWithoutExtension(new Uri(url).AbsolutePath);
            url = "https://api.bilibili.com/x/player/wbi/playurl?" + WbiSigner.Sign(parameters,
                Key(wbi.Str("img_url")), Key(wbi.Str("sub_url")), DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        }
        else
        {
            parameters["ep_id"] = item.EpisodeId.ToString();
            if (item.Kind == MediaKind.Bangumi) parameters["avid"] = item.Aid.ToString();
            url = "https://api.bilibili.com/" + (item.Kind == MediaKind.Course ? "pugv/player/web/playurl" : "pgc/player/web/playurl") + "?" +
                string.Join("&", parameters.Select(p => $"{p.Key}={Uri.EscapeDataString(p.Value)}"));
        }
        var data = await ApiAsync(url, ct);
        if (data.Num("is_drm") != 0 || data.Num("drm_tech_type") != 0)
            throw new BiliException("此内容的视频源受保护，无法导出为普通视频文件。");
        if (data.Num("is_preview") != 0) throw new BiliException("当前仅能获取试看内容，请登录有完整观看权限的账号。");
        return data;
    }

    public async Task<IReadOnlyList<MediaVariant>> GetVariantsAsync(MediaItem item, CancellationToken ct = default)
    {
        var data = await PlayInfoAsync(item, 127, ct);
        return Variants(data);
    }

    public static IReadOnlyList<MediaVariant> Variants(JsonElement data)
    {
        if (data.TryGetProperty("dash", out var dash) && dash.ValueKind == JsonValueKind.Object && dash.TryGetProperty("video", out var videos))
            return videos.EnumerateArray().Where(v => v.Num("codecid") == 7 || v.Str("codecs").StartsWith("avc"))
                .Select(v => new MediaVariant((int)v.Num("id"), QualityLabel((int)v.Num("id")), (int)v.Num("height"), "H.264"))
                .DistinctBy(v => v.Quality).OrderBy(v => v.Quality).ToArray();
        if (data.TryGetProperty("durl", out var durl) && durl.ValueKind == JsonValueKind.Array && durl.GetArrayLength() > 0)
            return [new((int)data.Num("quality"), QualityLabel((int)data.Num("quality")), 0, "H.264")];
        throw new BiliException("没有可导出的兼容视频流，请检查登录状态和观看权限。");
    }

    public async Task<DownloadPlan> GetPlanAsync(MediaItem item, int quality, CancellationToken ct = default)
    {
        var data = await PlayInfoAsync(item, quality, ct);
        var variants = Variants(data);
        var selected = variants.Where(v => v.Quality <= quality).MaxBy(v => v.Quality)
            ?? throw new BiliException("没有符合所选画质上限的 H.264 视频，请重新选择画质。");
        if (data.TryGetProperty("dash", out var dash) && dash.ValueKind == JsonValueKind.Object)
        {
            var video = dash.GetProperty("video").EnumerateArray().First(v => v.Num("id") == selected.Quality &&
                (v.Num("codecid") == 7 || v.Str("codecs").StartsWith("avc")));
            StreamPart? audio = null;
            if (dash.TryGetProperty("audio", out var audioArray) && audioArray.ValueKind == JsonValueKind.Array && audioArray.GetArrayLength() > 0)
            {
                var aac = audioArray.EnumerateArray().Where(a => a.Str("codecs").StartsWith("mp4a"));
                if (!aac.Any()) throw new BiliException("没有兼容的 AAC 音频，无法生成默认格式。");
                var a = aac.MaxBy(a => a.Num("bandwidth"));
                audio = Part(a, $"audio-{a.Num("id")}");
            }
            return new(selected, [Part(video, $"video-{item.Cid}-{selected.Quality}-avc")], audio);
        }
        return new(selected, data.GetProperty("durl").EnumerateArray()
            .Select((v, i) => new StreamPart($"progressive-{item.Cid}-{selected.Quality}-{i}", v.Str("url"), v.StringArray("backup_url"))).ToArray(), null);
    }

    private static StreamPart Part(JsonElement value, string key) => new(key,
        value.Str("baseUrl", value.Str("base_url")), value.StringArray("backupUrl").Concat(value.StringArray("backup_url")).Distinct().ToArray());
    public static string QualityLabel(int q) => q switch { 6 => "240p", 16 => "360p", 32 => "480p", 64 => "720p", 74 => "720p 60帧", 80 => "1080p", 112 => "1080p 高码率", 116 => "1080p 60帧", 120 => "4K", 125 => "HDR", 126 => "杜比视界", 127 => "8K", _ => $"画质 {q}" };
    public static Dictionary<string, string> ParseQuery(Uri uri) => uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
        .Select(x => x.Split('=', 2)).GroupBy(x => Uri.UnescapeDataString(x[0]))
        .ToDictionary(g => g.Key, g => Uri.UnescapeDataString(g.Last().ElementAtOrDefault(1) ?? ""));
    [GeneratedRegex(@"/video/(BV[0-9a-zA-Z]{10}|av\d+)", RegexOptions.IgnoreCase)] private static partial Regex VideoPath();
    [GeneratedRegex(@"/(ep|ss|md)(\d+)", RegexOptions.IgnoreCase)] private static partial Regex EpisodePath();
    public void Dispose() => http.Dispose();
}

public static class JsonFields
{
    public static string Str(this JsonElement element, string name, string fallback = "") =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? fallback : fallback;
    public static long Num(this JsonElement element, string name) => element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ?
        value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var n) ? n : value.ValueKind == JsonValueKind.True ? 1 : 0 : 0;
    public static string[] StringArray(this JsonElement e, string name) => e.TryGetProperty(name, out var array) && array.ValueKind == JsonValueKind.Array ?
        array.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToArray() : [];
}
