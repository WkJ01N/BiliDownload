using BiliDownload.Core;
using Xunit;
namespace BiliDownload.Tests;
public class RemoveTaskTests {
    private class BlockedMedia : IMediaService {
        public Task<IReadOnlyList<MediaCatalog>> ResolveAsync(string input, CancellationToken ct = default) => throw new NotSupportedException();
        public Task<IReadOnlyList<MediaVariant>> GetVariantsAsync(MediaItem item, CancellationToken ct = default) => throw new NotSupportedException();
        public async Task<DownloadPlan> GetPlanAsync(MediaItem item, int quality, CancellationToken ct = default) { await Task.Delay(Timeout.Infinite, ct); throw new NotSupportedException(); }
    }
    [Theory] [InlineData(DownloadState.Completed)] [InlineData(DownloadState.Paused)] [InlineData(DownloadState.Failed)]
    public async Task RemovalPersistsAndPreservesVideos(DownloadState state) {
        var dir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()); Directory.CreateDirectory(dir);
        try {
            var output = Path.Combine(dir,"video.mp4"); await File.WriteAllTextAsync(output, "keep");
            var store = new TaskStore(Path.Combine(dir,"data"));
            var id = Guid.NewGuid(); var cache = Path.Combine(dir,"cache",id.ToString("N")); Directory.CreateDirectory(cache); await File.WriteAllTextAsync(Path.Combine(cache,"part"), "temp");
            await store.SaveAsync(new(id, new("video:1","title","part","",MediaKind.Video,1,"BV1xx411c7mD",1,0,1,""),80,dir,state,FilePath:output));
            await using(var queue = new DownloadQueue(new BlockedMedia(),store,Path.Combine(dir,"cache"),()=>"missing-ffmpeg")) {
                await queue.RemoveAsync(id); await queue.RemoveAsync(id);
                Assert.Empty(queue.Snapshot()); Assert.Empty(store.Load()); Assert.False(Directory.Exists(cache)); Assert.Equal("keep",await File.ReadAllTextAsync(output));
            }
            Assert.Empty(store.Load());
        } finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(dir,true); }
    }
    [Fact] public async Task RemovingQueuedWorkDoesNotKillWorkersOrResurrectTask() {
        var dir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try {
            var store = new TaskStore(dir);
            await using var queue = new DownloadQueue(new BlockedMedia(),store,Path.Combine(dir,"cache"),()=>"missing-ffmpeg");
            var item = new MediaItem("video:1","title","part","",MediaKind.Video,1,"",1,0,1,"");
            for(var n=0;n<6;n++) { await queue.AddAsync([item with { Id = "video:"+n }],80,dir); }
            var ids = queue.Snapshot().Select(t=>t.Id).ToArray();
            await Task.WhenAll(ids.Select(queue.RemoveAsync));
            await Task.Delay(50); Assert.Empty(queue.Snapshot()); Assert.Empty(store.Load());
            await queue.AddAsync([item],80,dir); Assert.Single(queue.Snapshot());
        } finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); if(Directory.Exists(dir)) Directory.Delete(dir,true); }
    }
}
