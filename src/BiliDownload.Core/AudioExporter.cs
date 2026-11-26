using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public static class AudioExporter
{
    private static readonly HttpClient covers = new(new HttpClientHandler { UseCookies = false }) { Timeout = TimeSpan.FromSeconds(8) };
    private static BiliException NeedTool() => new("此格式需要完整版 FFmpeg，请在设置的组件管理中安装后重试。", BiliErrorKind.AudioComponent);

    private static async Task<bool> Supports(string executable, string option, string name, CancellationToken ct)
    {
        using var p = new Process { StartInfo = new(executable) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true } };
        p.StartInfo.ArgumentList.Add("-hide_banner"); p.StartInfo.ArgumentList.Add(option);
        try { p.Start(); } catch (System.ComponentModel.Win32Exception) { return false; }
        using var registration = ct.Register(() => { try { p.Kill(true); } catch (InvalidOperationException) { } });
        var output = p.StandardOutput.ReadToEndAsync(ct); var errors = p.StandardError.ReadToEndAsync(ct);
        await p.WaitForExitAsync(ct); await errors;
        return p.ExitCode == 0 && Regex.IsMatch(await output, @"(?m)^\s*\S+\s+" + Regex.Escape(name) + @"\s");
    }

    public static async Task CheckGifAsync(string executable, CancellationToken ct)
    {
        if (!await Supports(executable,"-encoders","gif",ct) || !await Supports(executable,"-filters","palettegen",ct) ||
            !await Supports(executable,"-filters","paletteuse",ct)) throw new BiliException("导出 GIF 需要完整版 FFmpeg，请在设置中安装后重试。", BiliErrorKind.AudioComponent);
    }
    public static async Task CheckAsync(string executable, AudioOutput output, CancellationToken ct)
    {
        if (output.Mp3Kbps != 0 && (!new[] { 320, 192, 128 }.Contains(output.Mp3Kbps) || !await Supports(executable, "-encoders", "libmp3lame", ct))) throw NeedTool();
        if ((output.Track.Mixed || output.Extension == ".opus" && output.Track.Container == "webm" || output.Extension == ".flac" && output.Track.Container is "audio/mp4" or "m4a") && !await Supports(executable, "-muxers", output.Mp3Kbps > 0 ? "mp3" : Muxer(output.Extension), ct)) throw NeedTool();
    }
    private static string Muxer(string ext) => ext switch { ".m4a" => "mp4", ".opus" => "opus", ".flac" => "flac", ".mp3" => "mp3", _ => throw NeedTool() };

    public static async Task ExportAsync(string executable, IReadOnlyList<string> inputs, AudioOutput output, MediaItem item, string path, CancellationToken ct)
    {
        var head = new byte[12];
        using (var file = File.OpenRead(inputs[0])) file.ReadExactly(head);
        var magic = Encoding.ASCII.GetString(head);
        var direct = inputs.Count == 1 && !output.Track.Mixed && output.Mp3Kbps == 0 && output.Extension switch {
            ".m4a" => magic.Substring(4, 4) == "ftyp", ".flac" => magic.StartsWith("fLaC"), ".opus" => magic.StartsWith("OggS"), _ => false };
        if (direct)
        {
            await using var input = File.OpenRead(inputs[0]); await using var dest = File.Create(path);
            await input.CopyToAsync(dest, ct);
        }
        else
        {
            var muxer = Muxer(output.Extension);
            if (!await Supports(executable, "-muxers", muxer, ct)) throw NeedTool();
            var args = new List<string> { "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file" };
            if (inputs.Count > 1)
            {
                var list = Path.Combine(Path.GetDirectoryName(path)!, "audio-segments.txt");
                await File.WriteAllLinesAsync(list, inputs.Select(f => "file '" + Path.GetFileName(f) + "'"), ct);
                args.AddRange(["-f","concat","-safe","1","-i",list]);
            }
            else args.AddRange(["-i",inputs[0]]);
            args.AddRange(["-map","0:a:0","-vn","-c:a",output.Mp3Kbps > 0 ? "libmp3lame" : "copy"]);
            if (output.Mp3Kbps > 0) args.AddRange(["-b:a",output.Mp3Kbps+"k","-ac",output.Track.Channels == 1 ? "1" : "2"]);
            if (muxer == "mp4") args.AddRange(["-movflags","+faststart"]);
            args.AddRange(["-f",muxer,path]);
            try { await MediaDownloader.RunAsync(executable, args, ct, requireAudio: item.X != null); }
            catch (BiliException ex) when (ex.Kind != BiliErrorKind.NoAudio && (output.Track.Mixed || output.Mp3Kbps == 0)) { throw new BiliException("音轨封装失败。请确认已安装完整版 FFmpeg，并检查源文件。", BiliErrorKind.AudioComponent); }
        }
        var tags = output.Track.Tags ?? new MusicTags(item.PartCount > 1 ? item.PartTitle : item.Title, Cover: item.Cover);
        byte[]? image = null;
        if (Uri.TryCreate(tags.Cover, UriKind.Absolute, out var url) && url.Scheme is "http" or "https")
            try {
                using var response = await covers.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
                response.EnsureSuccessStatusCode();
                await response.Content.LoadIntoBufferAsync(6 * 1024 * 1024, ct);
                image = await response.Content.ReadAsByteArrayAsync(ct);
            } catch (Exception e) when (!ct.IsCancellationRequested && e is HttpRequestException or OperationCanceledException or IOException) { }
        ct.ThrowIfCancellationRequested();
        using var tagged = TagLib.File.Create(path, TagLib.ReadStyle.None);
        tagged.Tag.Title = tags.Title;
        if (!string.IsNullOrWhiteSpace(tags.Artist)) tagged.Tag.Performers = [tags.Artist];
        if (!string.IsNullOrWhiteSpace(tags.Album)) tagged.Tag.Album = tags.Album;
        if (image is { Length: > 8 } && (image[0] == 0xff && image[1] == 0xd8 || image[0] == 0x89 && image[1] == 0x50))
            tagged.Tag.Pictures = [new TagLib.Picture(new TagLib.ByteVector(image)) { Type = TagLib.PictureType.FrontCover, MimeType = image[0] == 0xff ? "image/jpeg" : "image/png" }];
        tagged.Save();
    }
}
