using BiliDownload.Core;
using System.Text.Json;
using System.Text;
using Xunit;

namespace BiliDownload.Tests;
public class FileNamingTests {
    private static MediaItem Item(int count = 1, int index = 1, string part = "标题") => new("video:123", "标题", part, "", MediaKind.Video, 1, "BV1xx411c7mD", 123, 0, index, "", count);
    [Fact] public void DefaultsUseFullCatalogCount() {
        Assert.Equal("标题.mp4", FileNaming.Render(Item()));
        Assert.Equal("标题 P2 第二部分.mp4", FileNaming.Render(Item(3, 2, "第二部分")));
        Assert.Equal("标题 P1.mp4", FileNaming.Render(Item(3)));
        Assert.Equal("标题 P4 第四集.mp4", FileNaming.Render(Item(12, 4, "第四集") with { Kind = MediaKind.Bangumi }));
    }
    [Fact] public void TemplatesAndOverrides() {
        Assert.Equal("BV1xx411c7mD-2-第二部分.mp4", FileNaming.Render(Item(3,2,"第二部分"), "{bvid}-{index}-{part}"));
        Assert.Equal("我的视频.mp4", FileNaming.Render(Item(), "{title}", "我的视频.MP4"));
        Assert.Equal("{title}.mp4", FileNaming.Render(Item() with { Title = "{title}" }));
    }
    [Theory] [InlineData("")] [InlineData("{unknown}")] [InlineData("{title")] [InlineData("title}")]
    public void RejectsInvalidTemplates(string template) => Assert.Throws<BiliException>(() => FileNaming.Validate(template));
    [Theory] [InlineData("CON", "_CON.mp4")] [InlineData("a/b:c?. ", "a_b_c_.mp4")] [InlineData("...", "video.mp4")]
    public void CleansNames(string name, string expected) => Assert.Equal(expected, FileNaming.Normalize(name));
    [Fact] public void PreservesUnicode() {
        var name = FileNaming.Normalize(string.Concat(Enumerable.Repeat("中😀", 80)));
        Assert.Equal(100, name[..^4].EnumerateRunes().Count());
        Assert.DoesNotContain("�", name);
    }
    [Fact] public async Task ParallelPublicationNeverOverwrites() {
        var dir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString()); Directory.CreateDirectory(dir);
        try {
            File.WriteAllText(Path.Combine(dir, "视频.mp4"), "original");
            var paths = await Task.WhenAll(Enumerable.Range(0,8).Select(i => Task.Run(() => {
                var staging = Path.Combine(dir, i + ".tmp"); File.WriteAllText(staging, i.ToString());
                return FileNaming.MoveUnique(staging, dir, "视频.mp4");
            })));
            Assert.Equal(8, paths.Distinct().Count());
            Assert.Equal("original", File.ReadAllText(Path.Combine(dir,"视频.mp4")));
            Assert.True(File.Exists(Path.Combine(dir,"视频 (8).mp4")));
        } finally { Directory.Delete(dir, true); }
    }
    [Fact] public async Task TaskSnapshotAndOldRecordsRemainCompatible() {
        var dir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try {
            var store = new TaskStore(dir);
            var task = new DownloadTaskData(Guid.NewGuid(), Item(), 80, dir, State: DownloadState.Paused, OutputFileName: "自定义.mp4");
            await store.SaveAsync(task); Assert.Equal(task.OutputFileName, Assert.Single(store.Load()).OutputFileName);
            var json = System.Text.Json.Nodes.JsonNode.Parse(JsonSerializer.Serialize(task))!.AsObject();
            json.Remove("OutputFileName"); json.Remove("FinalFileName");
            Assert.Null(json.Deserialize<DownloadTaskData>()!.OutputFileName);
            var settings = JsonSerializer.Deserialize<AppSettings>("{\"DownloadDirectory\":\"C:/Videos\"}");
            Assert.Equal(FileNaming.DefaultTemplate, settings!.FileNameTemplate);
        } finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(dir, true); }
    }
}
