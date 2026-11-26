using System.Net;
using System.Text.Json;
using BiliDownload.Core;
using Xunit;

namespace BiliDownload.Tests;

public class XPostTests
{
    private const string Fixture = """
        {"id":"701","author":"Alice","handle":"alice","text":"第一行\n第二行","createdAt":"2026-11-01",
         "quotedUrl":"https://x.com/i/status/250","truncated":true,"items":[
            {"id":"a","index":1,"kind":"image","extension":".png","bytes":200},
            {"id":"b","index":2,"kind":"gif","extension":".mp4","audio":false},
            {"id":"c","index":3,"kind":"video","extension":".mp4","duration":12.3,"audio":true,
             "variants":[{"height":720,"bytes":1000,"format":"v720"}]}]}
        """;

    [Fact] public void DirectLinksAndClipboardRetainTargetAndDeduplicate()
    {
        var links = ClipboardPolicy.Links("https://mobile.twitter.com/alice/status/701/video/3?s=12 https://x.com/i/web/status/701/video/3 https://t.co/Abc");
        Assert.Equal(new[] { "https://x.com/i/status/701/video/3","https://t.co/Abc" }, links);
        Assert.Empty(ClipboardPolicy.Links("https://x.com.example.org/alice/status/701 https://t.co.example.org/A https://x.com:8080/a/status/1 https://x.com/i/spaces/701"));
    }
    [Fact] public void ManifestKeepsOrderSuffixTypesAndPostText()
    {
        using var json = JsonDocument.Parse(Fixture);
        var catalog = XPost.Read(json.RootElement,new("701","video",3));
        Assert.Equal("x:701:c",catalog.TargetItemId);
        Assert.Equal(new[] { ".png",".mp4",".mp4",".md" },catalog.Items.Select(i => i.X!.OutputExtension));
        var doc = catalog.Items.Last().X!;
        Assert.Contains("第一行\n第二行",doc.Markdown); Assert.Contains("https://x.com/i/status/250",doc.Markdown);
        Assert.False(catalog.Items[1].X!.HasAudio); Assert.Equal(12.3,catalog.Items[2].X!.Duration);
        Assert.False(ClipboardPolicy.ShouldAutoDownload(new("root"),1,[catalog]));
        Assert.Equal(4, ClipboardPolicy.VisibleItems(new("root"),[catalog]).Count);
        Assert.Throws<BiliException>(() => XPost.Read(json.RootElement,new("701","photo",3)));
        Assert.Equal(Path.Combine("base","alice-701"),doc.DirectoryUnder("base",false,false));
        Assert.Equal(Path.Combine("base","正文"),doc.DirectoryUnder("base",true,false));
    }
    [Fact] public void ScopedCookiesNeverIncludeOtherSources()
    {
        var data = ".twitter.com\tTRUE\t/\tTRUE\t0\tauth_token\tx\n#HttpOnly_.x.com\tTRUE\t/\tTRUE\t0\tct0\ty\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tz\n.evilx.com\tTRUE\t/\tTRUE\t0\tsid\tz";
        var cookies = YoutubeCookies.FilterX(data);
        Assert.Contains(".twitter.com",cookies); Assert.Contains(".x.com",cookies);
        Assert.DoesNotContain("youtube",cookies); Assert.DoesNotContain("evil",cookies);
        Assert.DoesNotContain("auth_token",YoutubeCookies.Filter(data));
    }
    [Fact] public void TextOnlyPostsAreTargetsAndMissingFormatsCannotAutoDownload()
    {
        using var text = JsonDocument.Parse("""{"id":"701","text":"单条正文","items":[]}""");
        var catalog = XPost.Read(text.RootElement,new("701"));
        Assert.Equal("x:701:markdown",catalog.TargetItemId);
        Assert.True(ClipboardPolicy.ShouldAutoDownload(new("root"),1,[catalog]));
        using var onlyHls = JsonDocument.Parse("""{"id":"701","items":[{"id":"v","index":1,"kind":"video","extension":".mp4","variants":[]}]}""");
        var unusable = XPost.Read(onlyHls.RootElement,new("701","video",1));
        Assert.False(unusable.Items[0].X!.Available);
        Assert.False(ClipboardPolicy.ShouldAutoDownload(new("root"),1,[unusable]));
    }
    private sealed class Redirect(string location) : HttpMessageHandler {
        public int Calls { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage req,CancellationToken ct) {
            Assert.Equal("t.co",req.RequestUri!.Host); Assert.Equal("https",req.RequestUri.Scheme); Calls++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.Found) { Headers = { Location = new Uri(location) } });
        }
    }
    [Theory]
    [InlineData("https://example.org/tweet")]
    [InlineData("https://t.co/A")]
    [InlineData("http://x.com/alice/status/123")]
    public async Task ShortLinksStopBeforeUnsafeOrRepeatedTargets(string location)
    {
        var handler = new Redirect(location); using var http = new HttpClient(handler);
        await Assert.ThrowsAsync<BiliException>(() => XLink.ExpandAsync(new Uri("https://t.co/A"),default,http));
        Assert.Equal(1,handler.Calls);
    }
    [Fact] public async Task ShortLinksReturnPostWithoutRequestingPostWithShortLinkClient()
    {
        var handler = new Redirect("https://x.com/alice/status/701/photo/1"); using var http = new HttpClient(handler);
        Assert.Equal(new XLink("701","photo",1),await XLink.ExpandAsync(new Uri("http://t.co/A"),default,http));
        Assert.Equal(1,handler.Calls);
    }
    [Fact] public async Task TextTaskUsesActualCompletedAttachmentAndNoMediaTools()
    {
        var root = Directory.CreateTempSubdirectory().FullName;
        var output = Path.Combine(root,"downloads"); Directory.CreateDirectory(output);
        var store = new TaskStore(root);
        using var json = JsonDocument.Parse(Fixture);
        var items = XPost.Read(json.RootElement,new("701")).Items;
        var image = Path.Combine(output,"a (1).png");
        await File.WriteAllBytesAsync(image,[1]);
        var done = new DownloadTaskData(Guid.NewGuid(),items[0],0,output,DownloadState.Completed,FilePath:image);
        await store.SaveAsync(done);
        using var bili = new BiliClient();
        var media = new MediaRouter(bili,new YoutubeEngine(() => throw new Exception(), "", () => null),() => false);
        var doc = items.Last() with { X = items.Last().X! with { Related = [done.Id.ToString()] } };
        await File.WriteAllTextAsync(Path.Combine(output,"post.md"),"existing");
        try {
            await using (var queue = new DownloadQueue(media,store,Path.Combine(root,"cache"),() => throw new Exception("unexpected FFmpeg"))) {
                await queue.AddAsync([doc],0,output,new Dictionary<string,string>{[doc.Id]="post.md"});
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                DownloadTaskData current;
                while ((current = queue.Snapshot().Single(t => t.Item.Id == doc.Id)).State is DownloadState.Queued or DownloadState.Downloading)
                    await Task.Delay(20,timeout.Token);
                Assert.Equal(DownloadState.Completed,current.State); Assert.Equal("post (1).md",current.FinalFileName);
                Assert.Contains("![a (1).png](a%20%281%29.png)",await File.ReadAllTextAsync(current.FilePath!));
            }
        } finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(root,true); }
    }
}
