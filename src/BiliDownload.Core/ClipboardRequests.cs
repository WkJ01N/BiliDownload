using System.Text.Json;

namespace BiliDownload.Core;

public static class ClipboardPolicy
{
    public static AppSettings Migrate(AppSettings settings) => settings.ClipboardBehaviorVersion >= 1 ? settings :
        settings with { MonitorClipboard = true, ClipboardBehaviorVersion = 1 };

    // Clipboard inspection deliberately does not resolve short URLs or call any API.
    public static string[] Links(string text) => LinkParser.Extract(text).Where(u =>
        u.Host.Equals("b23.tv", StringComparison.OrdinalIgnoreCase) ||
        u.AbsolutePath.StartsWith("/video/", StringComparison.OrdinalIgnoreCase) ||
        u.AbsolutePath.StartsWith("/bangumi/play/", StringComparison.OrdinalIgnoreCase) ||
        u.AbsolutePath.StartsWith("/bangumi/media/", StringComparison.OrdinalIgnoreCase) ||
        u.AbsolutePath.StartsWith("/cheese/play/", StringComparison.OrdinalIgnoreCase))
        .Select(u => {
            if (u.Host.Equals("b23.tv", StringComparison.OrdinalIgnoreCase)) return u.AbsoluteUri;
            var page = BiliClient.ParseQuery(u).GetValueOrDefault("p");
            return "https://www.bilibili.com" + u.AbsolutePath.TrimEnd('/') + (page == null ? "" : "?p=" + Uri.EscapeDataString(page));
        }).Distinct(StringComparer.Ordinal).ToArray();

    public static bool ShouldAutoDownload(AppSettings settings, int linkCount, IReadOnlyList<MediaCatalog> catalogs) =>
        settings.AutoDownloadOnNotificationClick && !settings.ExpandRelatedItems && linkCount == 1 && catalogs.Count == 1 &&
        catalogs[0].TargetItemId != null && catalogs[0].Items.Any(i => i.Id == catalogs[0].TargetItemId);

    public static IReadOnlyList<MediaItem> VisibleItems(AppSettings settings, IReadOnlyList<MediaCatalog> catalogs) =>
        catalogs.SelectMany(c => settings.ExpandRelatedItems || c.TargetItemId == null ? c.Items : c.Items.Where(i => i.Id == c.TargetItemId)).DistinctBy(i => i.Id).ToArray();
}

public sealed record ClipboardRequest(Guid Id, string[] Links, DateTimeOffset CreatedAt);

// Written by the single application instance. Consuming before dispatch also prevents COM activation replay.
public sealed class ClipboardRequestStore(string directory, TimeProvider? timeProvider = null)
{
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private readonly string path = Path.Combine(directory, "pending-notifications.json");
    private readonly object sync = new();
    public ClipboardRequest Create(string[] links)
    {
        lock (sync)
        {
            var entries = Load(); var request = new ClipboardRequest(Guid.NewGuid(), links.ToArray(), clock.GetUtcNow());
            entries.Add(request); Save(entries); return request;
        }
    }
    public ClipboardRequest? Consume(Guid id)
    {
        lock (sync)
        {
            var entries = Load(); var request = entries.FirstOrDefault(r => r.Id == id);
            entries.RemoveAll(r => r.Id == id); Save(entries); return request;
        }
    }
    private List<ClipboardRequest> Load()
    {
        try { return (JsonSerializer.Deserialize<List<ClipboardRequest>>(File.ReadAllText(path)) ?? [])
            .Where(r => r != null && r.CreatedAt <= clock.GetUtcNow() && clock.GetUtcNow() - r.CreatedAt < TimeSpan.FromHours(24))
            .Where(r => r.Links is { Length: > 0 } && r.Links.All(l => l != null && ClipboardPolicy.Links(l).Length == 1)).ToList(); }
        catch (Exception ex) when (ex is IOException or JsonException) { return []; }
    }
    private void Save(List<ClipboardRequest> entries)
    {
        Directory.CreateDirectory(directory);
        File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(entries)); File.Move(path + ".tmp", path, true);
    }
}
