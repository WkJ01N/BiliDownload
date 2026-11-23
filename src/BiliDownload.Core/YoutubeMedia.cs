using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public sealed record YoutubeLink(string? Video, string? Playlist)
{
    public string Canonical => Video == null ? "https://www.youtube.com/playlist?list=" + Playlist :
        "https://www.youtube.com/watch?v=" + Video + (Playlist == null ? "" : "&list=" + Playlist);

    public static YoutubeLink? Parse(Uri uri)
    {
        if (!uri.IsAbsoluteUri || uri.Scheme is not ("http" or "https") || !uri.IsDefaultPort || uri.UserInfo != "") return null;
        var path = uri.AbsolutePath.TrimEnd('/');
        var query = BiliClient.ParseQuery(uri);
        string? video;
        if (uri.IdnHost == "youtu.be") video = path.TrimStart('/');
        else if (uri.IdnHost is "youtube.com" or "www.youtube.com" or "m.youtube.com")
        {
            if (path == "/watch") video = query.GetValueOrDefault("v");
            else if (path.StartsWith("/shorts/", StringComparison.Ordinal)) video = path[8..];
            else if (path == "/playlist") video = null;
            else return null;
        }
        else return null;
        if (video != null && !Regex.IsMatch(video, @"\A[A-Za-z0-9_-]{11}\z")) return null;
        var list = query.GetValueOrDefault("list");
        if (list != null && !Regex.IsMatch(list, @"\A[A-Za-z0-9_-]{2,128}\z")) list = null;
        return video == null && list == null ? null : new(video, list);
    }
}

public sealed record PlaylistCursor(string Id, int Start);
public record DownloadedTracks(IReadOnlyList<string> Video, string? Audio, int Quality, string? Format);

public sealed class YoutubeEngine(Func<string?> executable, string script, Func<string?> cookies)
{
    public async Task<JsonElement> RunAsync(object request, CancellationToken ct, string? work = null, Action<double>? progress = null)
    {
        var python = executable() ?? throw new BiliException("请安装 YouTube 下载组件。", BiliErrorKind.YoutubeComponent);
        var directory = Path.GetDirectoryName(python)!;
        var start = new ProcessStartInfo(python) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8, WorkingDirectory = work ?? directory };
        foreach (string arg in new[] { "-I", "-B", "-X", "utf8", script, Path.Combine(directory, "yt-dlp"), Path.Combine(directory, "qjs.exe") })
            start.ArgumentList.Add(arg);
        var payload = JsonSerializer.SerializeToNode(request)!.AsObject();
        payload["cookies"] = cookies();
        using var process = Process.Start(start) ?? throw new BiliException("YouTube 组件无法启动。", BiliErrorKind.YoutubeComponent);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        if (work == null) deadline.CancelAfter(TimeSpan.FromMinutes(2));
        using var registration = deadline.Token.Register(() => { try { process.Kill(true); } catch (InvalidOperationException) { } });
        try
        {
            var errors = process.StandardError.BaseStream.CopyToAsync(Stream.Null);
            await process.StandardInput.WriteLineAsync(payload.ToJsonString().AsMemory(), deadline.Token);
            process.StandardInput.Close();
            JsonElement? result = null;
            while (await process.StandardOutput.ReadLineAsync(deadline.Token) is { } line)
            {
                using var doc = JsonDocument.Parse(line);
                var data = doc.RootElement;
                if (data.TryGetProperty("error", out var failure)) throw Failure(failure.GetString());
                if (data.TryGetProperty("progress", out var fraction)) progress?.Invoke(Math.Clamp(fraction.GetDouble(), 0, 1));
                if (data.TryGetProperty("result", out var value)) result = value.Clone();
            }
            await process.WaitForExitAsync(deadline.Token); await errors;
            deadline.Token.ThrowIfCancellationRequested();
            return result ?? throw Failure("Engine");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new BiliException("YouTube 响应超时，请稍后重试。"); }
        finally { try { if (!process.HasExited) process.Kill(true); } catch (InvalidOperationException) { } }
    }
    internal static BiliException Failure(string? kind) => kind switch
    {
        "SessionRequired" => new("该视频需要 YouTube 账号。请导入会话后重试。", BiliErrorKind.YoutubeSession),
        "SessionExpired" => new("YouTube 会话已失效或账号无权访问，请检查账号或替换会话。", BiliErrorKind.YoutubeSession),
        "Membership" => new("该账号没有此视频所需的频道会员资格。", BiliErrorKind.YoutubeSession),
        "Protected" => new("此视频受 DRM 保护，无法保存为 MP4。"),
        "Live" => new("直播尚未结束，请在回放发布后重试。"),
        "Restricted" => new("YouTube 暂时限制了请求，请稍后重试。"),
        "Network" => new("无法连接 YouTube 媒体服务，请检查网络后重试。"),
        "NoFormat" => new("没有符合画质上限的 H.264 和 AAC 格式。"),
        "Engine" => new("YouTube 下载组件无法运行，请在设置中检查组件。", BiliErrorKind.YoutubeComponent),
        _ => new("YouTube 视频或播放列表不可用。")
    };
}

public sealed class MediaRouter(BiliClient bili, YoutubeEngine youtube, Func<bool> expand) : IMediaService
{
    public async Task<IReadOnlyList<MediaCatalog>> ResolveAsync(string input, CancellationToken ct = default)
    {
        var links = LinkParser.ExtractSupported(input);
        if (links.Count == 0) throw new BiliException("请粘贴 B 站或 YouTube 视频链接。");
        var results = new List<MediaCatalog>();
        foreach (var uri in links)
        {
            if (YoutubeLink.Parse(uri) is not { } link) { results.AddRange(await bili.ResolveAsync(uri.AbsoluteUri, ct)); continue; }
            if (link.Playlist != null && (link.Video == null || expand()))
            {
                var page = await PageAsync(new(link.Playlist, 1), ct);
                var items = page.Items.ToList();
                while (page.More != null && (items.Count == 0 || link.Video != null && items.All(i => i.YoutubeId != link.Video)))
                {
                    page = await PageAsync(page.More, ct);
                    items.AddRange(page.Items);
                }
                if (link.Video != null && items.All(i => i.YoutubeId != link.Video))
                    items.Add(ToItem(await Inspect(link.Video, ct)));
                results.Add(page with { Items = items, TargetItemId = link.Video == null ? null : "yt:" + link.Video });
            }
            else
            {
                var info = await Inspect(link.Video!, ct);
                var item = ToItem(info);
                results.Add(new(item.Title, [item], item.Id, InitialVariants: Variants(info)));
            }
        }
        return results;
    }
    public async Task<MediaCatalog> PageAsync(PlaylistCursor cursor, CancellationToken ct)
    {
        var info = await youtube.RunAsync(new { op = "playlist", id = cursor.Id, start = cursor.Start }, ct);
        var next = (int)info.Num("next");
        var items = info.GetProperty("items").EnumerateArray().Select(row => ToItem(row, cursor.Id)).ToArray();
        return new(info.Str("title"), items, More: next > cursor.Start ? new(cursor.Id, next) : null);
    }
    private static MediaItem ToItem(JsonElement row, string? playlist = null)
    {
        var id = row.Str("id"); var title = row.Str("title");
        return new("yt:" + id, title, title, row.Str("cover"), MediaKind.Video, 0, "", 0, 0, (int)row.Num("index"),
            new YoutubeLink(id, null).Canonical, 1, id, playlist);
    }
    private Task<JsonElement> Inspect(string id, CancellationToken ct) => youtube.RunAsync(new { op = "inspect", id }, ct);
    private static MediaVariant[] Variants(JsonElement info) => info.GetProperty("variants").EnumerateArray().Select(v =>
        new MediaVariant((int)v.Num("height"), v.Num("height") + "p", (int)v.Num("height"), "H.264",
            v.Num("bytes") is > 0 and var bytes ? bytes : null, v.Str("format"))).ToArray();
    public async Task<IReadOnlyList<MediaVariant>> GetVariantsAsync(MediaItem item, CancellationToken ct = default) =>
        item.YoutubeId != null ? Variants(await Inspect(item.YoutubeId, ct)) : await bili.GetVariantsAsync(item, ct);
    public Task<DownloadPlan> GetPlanAsync(MediaItem item, int quality, CancellationToken ct = default) =>
        item.YoutubeId == null ? bili.GetPlanAsync(item, quality, ct) : throw new InvalidOperationException("YouTube uses the component transfer engine.");
    public async Task<DownloadedTracks> FetchAsync(DownloadTaskData task, string work, Action<double> progress, CancellationToken ct)
    {
        var result = await youtube.RunAsync(new { op = "fetch", id = task.Item.YoutubeId, height = task.Quality, format = task.FormatId }, ct, work, progress);
        return new([Path.Combine(work, "video.track")], result.GetProperty("audio").GetBoolean() ? Path.Combine(work, "audio.track") : null,
            (int)result.Num("height"), result.Str("format"));
    }
}
