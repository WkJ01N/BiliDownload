using System.Text.Json;
namespace BiliDownload.Core;
public static class MediaSize {
    public static long? Estimate(JsonElement data, JsonElement video) {
        var dash = data.GetProperty("dash");
        var duration = dash.TryGetProperty("duration", out var d) && d.TryGetDouble(out var seconds) ? seconds : data.Num("timelength") / 1000.0;
        var bandwidth = video.Num("bandwidth");
        if (bandwidth <= 0 || duration <= 0 || !double.IsFinite(duration)) return null;
        if (dash.TryGetProperty("audio", out var audio) && audio.ValueKind == JsonValueKind.Array && audio.GetArrayLength() > 0) {
            var aac = audio.EnumerateArray().Where(a => a.Str("codecs").StartsWith("mp4a")).ToArray();
            if (aac.Length == 0 || aac.Max(a => a.Num("bandwidth")) <= 0) return null;
            bandwidth += aac.Max(a => a.Num("bandwidth"));
        }
        var bytes = duration * bandwidth / 8;
        return bytes > 0 && bytes < long.MaxValue ? (long)Math.Ceiling(bytes) : null;
    }
    public static long? Progressive(JsonElement durl) {
        var sizes = durl.EnumerateArray().Select(p => p.Num("size")).ToArray();
        return sizes.Length > 0 && sizes.All(s => s > 0) ? sizes.Sum() : null;
    }
    public static string Format(long? bytes) => bytes is > 0 ? bytes >= 1073741824 ? $"约 {bytes / 1073741824.0:F2} GiB" : $"约 {bytes / 1048576.0:F1} MiB" : "大小未知";
}
