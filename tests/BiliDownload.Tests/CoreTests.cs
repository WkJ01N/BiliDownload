using BiliDownload.Core;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using Xunit;

namespace BiliDownload.Tests;

public sealed class CoreTests
{
    [Theory]
    [InlineData("【视频】 https://b23.tv/abcd。", "b23.tv")]
    [InlineData("https://www.bilibili.com/video/BV1xx411c7mD?p=2", "www.bilibili.com")]
    [InlineData("BV1xx411c7mD", "www.bilibili.com")]
    public void ExtractsShareText(string text, string host) => Assert.Equal(host, Assert.Single(LinkParser.Extract(text)).Host);
    [Theory]
    [InlineData("https://bilibili.com.attacker.test/video/BV1xx411c7mD")]
    [InlineData("https://b23.tv:1234/x")]
    [InlineData("https://example.com")]
    [InlineData("不是链接")]
    public void RejectsUnsupportedLinks(string text) => Assert.Empty(LinkParser.Extract(text));
    [Fact] public void DeduplicatesLinks() => Assert.Single(LinkParser.Extract("https://b23.tv/a https://b23.tv/a"));
    [Fact] public void SanitizesNames() => Assert.Equal("a_b_c_", LinkParser.SafeName("a/b:c?."));
    [Fact] public void WbiMatchesPublishedVector()
    {
        var values = new Dictionary<string, string> { ["foo"] = "114", ["bar"] = "514", ["zab"] = "1919810" };
        Assert.Equal("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            WbiSigner.Sign(values, "7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45", 1702204169));
    }
    [Fact] public void FiltersOutIncompatibleCodecs()
    {
        using var doc = JsonDocument.Parse("""{"dash":{"video":[{"id":80,"height":1080,"codecid":7},{"id":120,"height":2160,"codecid":12}]}}""");
        Assert.Equal(80, Assert.Single(BiliClient.Variants(doc.RootElement)).Quality);
    }
    [Fact] public void RejectsMissingVideo()
    {
        using var doc = JsonDocument.Parse("{}"); Assert.Throws<BiliException>(() => BiliClient.Variants(doc.RootElement));
    }
    [Fact] public async Task StoreRoundTripsTasks()
    {
        var directory = Temp();
        try
        {
            var store = new TaskStore(directory); var item = TaskItem(directory);
            await store.SaveAsync(item); await store.SaveAsync(item with { State = DownloadState.Paused });
            Assert.Equal(DownloadState.Paused, Assert.Single(store.Load()).State);
        }
        finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(directory, true); }
    }
    [Fact] public async Task DownloaderRestartsWhenRangeIgnored()
    {
        var dir = Temp(); var file = Path.Combine(dir, "video.part");
        try
        {
            await File.WriteAllTextAsync(file, "OLD");
            await File.WriteAllTextAsync(file + ".json", """{"Key":"v","ETag":"\"abc\"","Modified":null,"Total":6}""");
            using var downloader = new MediaDownloader(new Handler(request => {
                Assert.Equal(3, request.Headers.Range!.Ranges.Single().From);
                return new(HttpStatusCode.OK) { Content = new StringContent("NEW123") };
            }));
            await downloader.DownloadPartAsync(new("v", "https://cdn.example/video", []), file, _ => { }, default);
            Assert.Equal("NEW123", await File.ReadAllTextAsync(file));
        }
        finally { Directory.Delete(dir, true); }
    }
    [Fact] public async Task DownloaderAppendsValidatedRange()
    {
        var dir = Temp(); var file = Path.Combine(dir, "video.part");
        try
        {
            await File.WriteAllTextAsync(file, "ABC");
            await File.WriteAllTextAsync(file + ".json", """{"Key":"v","ETag":"\"abc\"","Modified":null,"Total":6}""");
            using var downloader = new MediaDownloader(new Handler(request => {
                Assert.Equal("\"abc\"", request.Headers.IfRange!.EntityTag!.Tag);
                var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = new StringContent("DEF") };
                response.Content.Headers.ContentRange = new ContentRangeHeaderValue(3, 5, 6); return response;
            }));
            await downloader.DownloadPartAsync(new("v", "https://cdn.example/video", []), file, _ => { }, default);
            Assert.Equal("ABCDEF", await File.ReadAllTextAsync(file));
        }
        finally { Directory.Delete(dir, true); }
    }
    [Fact] public async Task DownloaderRejectsIncorrectRange()
    {
        var dir = Temp();
        try
        {
            using var downloader = new MediaDownloader(new Handler(_ => {
                var response = new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = new StringContent("X") };
                response.Content.Headers.ContentRange = new ContentRangeHeaderValue(4, 4, 5); return response;
            }));
            await Assert.ThrowsAsync<BiliException>(() => downloader.DownloadPartAsync(new("v", "https://cdn.example/video", []), Path.Combine(dir, "v"), _ => { }, default));
        }
        finally { Directory.Delete(dir, true); }
    }
    private static string Temp() { var path = Path.Combine(Path.GetTempPath(), "bilidownload-tests-" + Guid.NewGuid()); Directory.CreateDirectory(path); return path; }
    private static DownloadTaskData TaskItem(string directory) => new(Guid.NewGuid(), new("video:1", "测试", "P1", "", MediaKind.Video, 1, "BV1xx411c7mD", 1, 0, 1, "https://www.bilibili.com"), 80, directory);
    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(respond(request));
    }
}
