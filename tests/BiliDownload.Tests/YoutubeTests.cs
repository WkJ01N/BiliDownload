using BiliDownload.Core;
using System.Text.Json;
using Xunit;

namespace BiliDownload.Tests;

public class YoutubeTests
{
    [Theory]
    [InlineData("https://youtu.be/jNQXAC9IVRw?si=test")]
    [InlineData("https://m.youtube.com/shorts/jNQXAC9IVRw/")]
    [InlineData("https://m.youtube.com/live/jNQXAC9IVRw/")]
    [InlineData("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=10")]
    public void SharedLinksHaveOneVideoIdentity(string url)
    {
        Assert.Equal("https://www.youtube.com/watch?v=jNQXAC9IVRw", ClipboardPolicy.Links("分享 " + url).Single());
        Assert.Empty(LinkParser.Extract(url));
    }
    [Theory]
    [InlineData("https://youtube.com.example.com/watch?v=jNQXAC9IVRw")]
    [InlineData("https://example.com/watch?v=jNQXAC9IVRw")]
    [InlineData("https://youtube.com:8080/watch?v=jNQXAC9IVRw")]
    [InlineData("https://youtube.com/channel/UC000000000")]
    [InlineData("https://youtube.com/watch?v=bad")]
    [InlineData("https://www.youtube.com/live/?list=PLtest")]
    [InlineData("https://www.youtube.com/live/not-a-video-id")]
    [InlineData("https://www.youtube.com/live/N2rr3OXrR8s/extra")]
    public void InvalidLinksAreIgnored(string url) => Assert.Empty(ClipboardPolicy.Links(url));

    [Fact] public void LiveReplayShareUsesTheWatchIdentity()
    {
        const string share = "https://www.youtube.com/live/N2rr3OXrR8s?si=Wn4JMg9g1UfVRoMg";
        const string watch = "https://www.youtube.com/watch?v=N2rr3OXrR8s";
        Assert.Equal(watch, Assert.Single(ClipboardPolicy.Links($"回放 {share} {watch}")));
        Assert.Equal(watch + "&list=PLtest", YoutubeLink.Parse(new Uri(share + "&list=PLtest"))!.Canonical);
    }

    [Fact] public void PlaylistAndVideoArePreserved()
    {
        var url = "https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLtest";
        Assert.Equal(url, Assert.Single(ClipboardPolicy.Links(url)));
        var result = YoutubeLink.Parse(new Uri("https://youtube.com/playlist?list=PLtest"));
        Assert.Null(result!.Video);
        Assert.Equal("PLtest", result.Playlist);
    }
    [Fact] public void ImportedSessionIsScopedAndExpires()
    {
        const string rows = "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tvalue\n" +
            ".google.com\tTRUE\t/\tTRUE\t0\tOTHER\tsecret\n.youtube.com\tTRUE\t/\tTRUE\t10\tOLD\told\n";
        var result = YoutubeCookies.Filter(rows, 11);
        Assert.Contains("\tSID\tvalue", result);
        Assert.DoesNotContain("OTHER", result);
        Assert.DoesNotContain("OLD", result);
        Assert.Throws<BiliException>(() => YoutubeCookies.Filter("# header only"));
    }
    [Fact] public void OldTaskAndNewFormatRoundTrip()
    {
        var item = new MediaItem("yt:jNQXAC9IVRw", "Video", "Video", "", MediaKind.Video, 0, "", 0, 0, 4, "", 2, "jNQXAC9IVRw");
        Assert.Equal("4-jNQXAC9IVRw.mp4", FileNaming.Render(item, "{index}-{id}"));
        var task = new DownloadTaskData(Guid.NewGuid(), item, 480, "videos", FormatId: "135+140");
        Assert.Equal(task, JsonSerializer.Deserialize<DownloadTaskData>(JsonSerializer.Serialize(task)));
        var legacy = System.Text.Json.Nodes.JsonNode.Parse(JsonSerializer.Serialize(task))!.AsObject();
        legacy.Remove("FormatId"); legacy["Item"]!.AsObject().Remove("YoutubeId");
        Assert.Null(legacy.Deserialize<DownloadTaskData>()!.Item.YoutubeId);
    }
}
