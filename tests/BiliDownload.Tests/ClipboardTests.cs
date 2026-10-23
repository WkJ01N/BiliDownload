using System.Text.Json;
using BiliDownload.Core;
using Xunit;

namespace BiliDownload.Tests;

public sealed class ClipboardTests
{
    private const string Link = "https://www.bilibili.com/video/BV1xx411c7mD?p=2";
    [Fact] public void RecognizesOnlyPlayableLinksAndRemovesShareTracking()
    {
        Assert.Equal(Link, Assert.Single(ClipboardPolicy.Links(Link + "&share_source=copy_link")));
        Assert.Empty(ClipboardPolicy.Links("https://space.bilibili.com/1 https://live.bilibili.com/1 https://www.bilibili.com/"));
        Assert.Single(ClipboardPolicy.Links("分享 https://b23.tv/123"));
        Assert.Single(ClipboardPolicy.Links("BV1xx411c7mD"));
    }
    [Fact] public void MigrationEnablesMonitoringOnceAndPreservesLaterChoice()
    {
        var old = JsonSerializer.Deserialize<AppSettings>("""{"DownloadDirectory":"D:/Videos","MonitorClipboard":false} """)!;
        var migrated = ClipboardPolicy.Migrate(old);
        Assert.True(migrated.MonitorClipboard); Assert.True(migrated.AutoDownloadOnNotificationClick); Assert.False(migrated.ExpandRelatedItems);
        Assert.Equal(migrated with { MonitorClipboard = false }, ClipboardPolicy.Migrate(migrated with { MonitorClipboard = false }));
    }
    [Fact] public void CollectionsAndExpandedSelectionAlwaysRequireConfirmation()
    {
        var item = new MediaItem("video:2", "x", "P2", "", MediaKind.Video, 1, "BV1xx411c7mD", 2, 0, 2, Link);
        MediaCatalog[] catalogs = [new("x", [item], item.Id)]; var settings = new AppSettings("D:/Videos");
        Assert.True(ClipboardPolicy.ShouldAutoDownload(settings, 1, catalogs));
        Assert.False(ClipboardPolicy.ShouldAutoDownload(settings with { AutoDownloadOnNotificationClick = false }, 1, catalogs));
        Assert.False(ClipboardPolicy.ShouldAutoDownload(settings with { ExpandRelatedItems = true }, 1, catalogs));
        Assert.False(ClipboardPolicy.ShouldAutoDownload(settings, 2, catalogs));
        Assert.False(ClipboardPolicy.ShouldAutoDownload(settings, 1, [catalogs[0] with { TargetItemId = null }]));
        Assert.False(ClipboardPolicy.ShouldAutoDownload(settings, 1, [catalogs[0] with { TargetItemId = "missing" }]));
    }
    [Fact] public void EachNotificationKeepsItsOwnLinkAcrossRestartAndConsumesOnce()
    {
        var directory = Path.Combine(Path.GetTempPath(), "bili-notifications-" + Guid.NewGuid());
        try {
            var store = new ClipboardRequestStore(directory);
            string[] links = [Link]; var first = store.Create(links); links[0] = "https://b23.tv/modified";
            var second = store.Create(["https://b23.tv/second"]);
            var restarted = new ClipboardRequestStore(directory);
            Assert.Equal(Link, Assert.Single(restarted.Consume(first.Id)!.Links));
            Assert.Null(restarted.Consume(first.Id)); Assert.NotNull(restarted.Consume(second.Id));
        } finally { Directory.Delete(directory, true); }
    }
    [Fact] public void ExpiredNotificationCannotStartDownload()
    {
        var directory = Path.Combine(Path.GetTempPath(), "bili-notifications-" + Guid.NewGuid());
        try {
            var clock = new Clock(); var store = new ClipboardRequestStore(directory, clock); var request = store.Create([Link]);
            clock.Now = clock.Now.AddHours(24); Assert.Null(store.Consume(request.Id));
        } finally { Directory.Delete(directory, true); }
    }
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }
}
