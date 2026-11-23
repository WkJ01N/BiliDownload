using System.Text.Json;

namespace BiliDownload.Core;

public record MusicTags(string Title, string Artist = "", string Album = "", string Cover = "");
public record AudioTrack(string Id, string Codec, int Bitrate, string Language, int Channels,
    double Duration, bool Mixed, long? DownloadBytes, long? AudioBytes, MusicTags? Tags = null, string Container = "", bool Drc = false)
{
    public string Extension => Codec switch { "Opus" => ".opus", "FLAC" => ".flac", "MP3" => ".mp3", _ => ".m4a" };
    public string Label => string.Join("  ", new[] { Codec switch { "FLAC" => "无损 FLAC", "E-AC-3" => "杜比 E-AC-3", _ => Codec }, Drc ? "动态压缩" : "", Bitrate > 0 ? $"{Bitrate} kb/s" : "", Language,
        Channels > 2 ? $"{Channels} 声道" : "", MediaSize.Format(DownloadBytes) }.Where(s => s.Length > 0));
}
public record AudioOutput(AudioTrack Track, int Mp3Kbps = 0)
{
    public string Extension => Mp3Kbps == 0 ? Track.Extension : ".mp3";
    public long? EstimatedBytes => Mp3Kbps == 0 ? Track.AudioBytes : Track.Duration > 0 ? (long)Math.Ceiling(Track.Duration * Mp3Kbps * 125) : null;
    public string Summary => (Track.Mixed ? "需下载整段视频  " : "") + Track.Label +
        (Track.Mixed || Mp3Kbps > 0 ? "  成品 " + MediaSize.Format(EstimatedBytes) : "");
}
public static class AudioMedia
{
    public static IReadOnlyList<(AudioTrack Track, StreamPart[] Parts)> BiliTracks(JsonElement data, long cid)
    {
        var tracks = new List<(AudioTrack Track, StreamPart[] Parts)>();
        var seconds = data.Num("timelength") / 1000d;
        if (data.TryGetProperty("dash", out var dash) && dash.ValueKind == JsonValueKind.Object)
        {
            if (dash.TryGetProperty("duration", out var duration) && duration.ValueKind == JsonValueKind.Number && duration.GetDouble() > 0) seconds = duration.GetDouble();
            void Add(JsonElement node)
            {
                if (node.ValueKind == JsonValueKind.Array) { foreach (var child in node.EnumerateArray()) Add(child); return; }
                if (node.ValueKind != JsonValueKind.Object || node.Num("drm_tech_type") != 0) return;
                var raw = node.Str("codecs").ToLowerInvariant();
                var codec = raw.StartsWith("mp4a") ? "AAC" : raw switch { "flac" => "FLAC", "ec-3" or "eac3" => "E-AC-3", "opus" => "Opus", _ => "" };
                var key = $"{node.Num("id")}-{codec}";
                var url = node.Str("baseUrl", node.Str("base_url"));
                if (codec == "" || url == "") return;
                var bits = node.Num("bandwidth");
                long? size = bits > 0 && seconds > 0 ? (long)Math.Ceiling(bits * seconds / 8) : null;
                var track = new AudioTrack(key, codec, (int)Math.Round(bits / 1000d), node.Str("lang"), (int)node.Num("channels"), seconds, false, size, size, Container: node.Str("mimeType",node.Str("mime_type","audio/mp4")));
                tracks.Add((track, [new($"audio-{cid}-{key}", url, node.StringArray("backupUrl").Concat(node.StringArray("backup_url")).Distinct().ToArray())]));
            }
            if (dash.TryGetProperty("audio", out var ordinary)) Add(ordinary);
            foreach (var group in new[] { "flac", "dolby" })
                if (dash.TryGetProperty(group, out var extra) && extra.ValueKind == JsonValueKind.Object && extra.TryGetProperty("audio", out var audio)) Add(audio);
        }
        if (tracks.Count > 0) return tracks.DistinctBy(x => x.Track.Id).OrderByDescending(x => x.Track.Codec == "FLAC")
            .ThenByDescending(x => x.Track.Codec == "E-AC-3").ThenByDescending(x => x.Track.Bitrate).ToArray();
        if (data.TryGetProperty("durl", out var list) && list.ValueKind == JsonValueKind.Array && list.GetArrayLength() > 0)
        {
            var key = $"mixed-{data.Num("quality")}";
            var parts = list.EnumerateArray().Select((v, i) => new StreamPart($"audio-{cid}-{key}-{i}", v.Str("url"), v.StringArray("backup_url"))).ToArray();
            tracks.Add((new(key, "内置音轨", 0, "", 0, seconds, true, MediaSize.Progressive(list), null), parts));
        }
        return tracks;
    }
    public static AudioTrack[] YoutubeTracks(JsonElement info)
    {
        double Number(JsonElement v, string key) => v.TryGetProperty(key, out var n) && n.ValueKind == JsonValueKind.Number ? n.GetDouble() : 0;
        var tags = new MusicTags(info.Str("trackTitle") is {Length: >0} title ? title : info.Str("title"), info.Str("artist"), info.Str("album"), info.Str("cover"));
        return info.GetProperty("variants").EnumerateArray().Select(v => new AudioTrack(v.Str("format"), v.Str("codec"), (int)v.Num("bitrate"),
            v.Str("language"), (int)v.Num("channels"), Number(v,"duration"), v.GetProperty("mixed").GetBoolean(),
            v.Num("bytes") is >0 and var size ? size : null, v.Num("audioBytes") is >0 and var bytes ? bytes : null, tags, v.Str("container"), v.Num("drc") != 0)).ToArray();
    }
}
