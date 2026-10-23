using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace BiliDownload.Core;

public sealed class MediaDownloader : IDisposable
{
    private readonly HttpClient http;
    private record Checkpoint(string Key, string? ETag, DateTimeOffset? Modified, long? Total);
    public MediaDownloader(HttpMessageHandler? handler = null)
    {
        http = new(handler ?? new HttpClientHandler { UseCookies = false }) { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(BiliClient.UserAgent);
        http.DefaultRequestHeaders.Referrer = new Uri("https://www.bilibili.com/");
    }
    public static async Task CheckFfmpegAsync(string executable, CancellationToken ct)
    {
        try { await RunAsync(executable, ["-version"], ct); }
        catch (System.ComponentModel.Win32Exception) { throw new BiliException("找不到 FFmpeg，请在设置中选择 ffmpeg.exe，或将 FFmpeg 加入 PATH。"); }
    }

    public async Task DownloadPartAsync(StreamPart part, string file, Action<double> progress, CancellationToken ct)
    {
        Exception? last = null;
        foreach (var url in new[] { part.Url }.Concat(part.Backups))
        {
            try { await TransferAsync(part.Key, url, file, progress, ct); return; }
            catch (HttpRequestException ex) { last = ex; }
        }
        if (last is HttpRequestException { StatusCode: HttpStatusCode.PreconditionFailed })
            throw new BiliException("视频源请求受限，请稍后重试。未登录时可尝试登录后重试，登录不保证解除限制。", BiliErrorKind.RequestRestricted, httpStatus: 412);
        throw last ?? new BiliException("没有可用的视频下载地址。");
    }

    private async Task TransferAsync(string key, string url, string file, Action<double> progress, CancellationToken ct)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http"))
            throw new BiliException("视频源地址无效。");
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        Checkpoint? checkpoint = null;
        if (File.Exists(file + ".json"))
        {
            try { checkpoint = JsonSerializer.Deserialize<Checkpoint>(await File.ReadAllTextAsync(file + ".json", ct)); }
            catch (JsonException) { /* Interrupted checkpoint write: restart this part. */ }
        }
        var offset = File.Exists(file) ? new FileInfo(file).Length : 0;
        if (checkpoint?.Key != key || (checkpoint.ETag == null && checkpoint.Modified == null)) offset = 0;
        if (offset > 0 && offset == checkpoint?.Total) { progress(1); return; }
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        if (offset > 0)
        {
            request.Headers.Range = new RangeHeaderValue(offset, null);
            request.Headers.IfRange = checkpoint!.ETag is { } etag ? new RangeConditionHeaderValue(new EntityTagHeaderValue(etag)) :
                new RangeConditionHeaderValue(checkpoint.Modified!.Value);
        }
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(TimeSpan.FromSeconds(60));
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
        if (response.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable)
        {
            File.Delete(file + ".json"); File.Delete(file);
            throw new HttpRequestException("远程视频发生变化，需要重新下载。", null, response.StatusCode);
        }
        response.EnsureSuccessStatusCode();
        if (offset > 0 && response.StatusCode != HttpStatusCode.PartialContent) offset = 0;
        if (response.StatusCode == HttpStatusCode.PartialContent && response.Content.Headers.ContentRange?.From != offset)
            throw new BiliException("服务器返回的断点位置不匹配，请重试。");
        var total = response.Content.Headers.ContentRange?.Length ?? (response.Content.Headers.ContentLength is { } length ? offset + length : (long?)null);
        var tag = response.Headers.ETag is { IsWeak: false } strong ? strong.Tag : null;
        var meta = new Checkpoint(key, tag, response.Content.Headers.LastModified, total);
        await using var output = new FileStream(file, offset == 0 ? FileMode.Create : FileMode.Append, FileAccess.Write, FileShare.Read, 65536, true);
        await File.WriteAllTextAsync(file + ".json", JsonSerializer.Serialize(meta), ct);
        await using var source = await response.Content.ReadAsStreamAsync(deadline.Token);
        var buffer = new byte[65536]; long received = offset;
        while (true)
        {
            deadline.CancelAfter(TimeSpan.FromSeconds(60));
            var count = await source.ReadAsync(buffer, deadline.Token);
            if (count == 0) break;
            await output.WriteAsync(buffer.AsMemory(0, count), ct); received += count;
            progress(total is > 0 ? (double)received / total.Value : 0);
        }
        if (total.HasValue && received != total.Value) throw new HttpRequestException("下载中断，收到的文件长度与服务器不符。");
        if (received == 0) throw new BiliException("服务器返回了空视频文件。");
        progress(1);
    }

    public static async Task MergeAsync(string ffmpeg, IReadOnlyList<string> videos, string? audio, string output, CancellationToken ct)
    {
        var args = new List<string> { "-hide_banner", "-loglevel", "error", "-nostdin", "-y" };
        if (videos.Count == 1) args.AddRange(["-i", videos[0]]);
        else
        {
            // Generated local basenames only; no shell or user text enters the concat file.
            var concat = Path.Combine(Path.GetDirectoryName(videos[0])!, "segments.txt");
            await File.WriteAllLinesAsync(concat, videos.Select(v => "file '" + Path.GetFileName(v) + "'"), ct);
            args.AddRange(["-f", "concat", "-safe", "1", "-i", concat]);
        }
        if (audio != null) args.AddRange(["-i", audio, "-map", "0:v:0", "-map", "1:a:0"]);
        args.AddRange(["-c", "copy", "-movflags", "+faststart", "-f", "mp4", output]);
        await RunAsync(ffmpeg, args, ct);
        if (!File.Exists(output) || new FileInfo(output).Length == 0) throw new BiliException("合并未生成有效文件。");
    }
    private static async Task RunAsync(string executable, IEnumerable<string> arguments, CancellationToken ct)
    {
        using var process = new Process { StartInfo = new ProcessStartInfo(executable) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true } };
        foreach (var argument in arguments) process.StartInfo.ArgumentList.Add(argument);
        process.Start();
        using var registration = ct.Register(() => { try { if (!process.HasExited) process.Kill(true); } catch (InvalidOperationException) { } });
        var errors = process.StandardError.ReadToEndAsync(ct);
        var output = process.StandardOutput.ReadToEndAsync(ct);
        await process.WaitForExitAsync(ct); await output; await errors;
        // Do not surface stderr: it can contain signed source URLs or local sensitive paths.
        if (process.ExitCode != 0) throw new BiliException("FFmpeg 处理失败，请确认媒体源完整、目标目录可写且 FFmpeg 可运行。");
    }
    public void Dispose() => http.Dispose();
}
