using BiliDownload.Core;
using System.Net;
using Xunit;

namespace BiliDownload.Tests;

public sealed class ApiTests
{
    private const string View = """{"code":0,"data":{"aid":1,"bvid":"BV1xx411c7mD","title":"测试视频","pic":"","pages":[{"cid":11,"part":"一"},{"cid":12,"part":"二"}]}}""";
    private const string Episode = """{"code":0,"result":{"title":"测试系列","cover":"","episodes":[{"id":1,"aid":2,"cid":3,"title":"第一集","long_title":"开始"}]}}""";
    private const string Nav = """{"code":-101,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}""";
    private const string Play = """{"code":0,"data":{"dash":{"video":[{"id":80,"height":1080,"codecid":7,"codecs":"avc1","baseUrl":"https://cdn.example/v"}],"audio":[{"id":30280,"bandwidth":192000,"codecs":"mp4a.40.2","baseUrl":"https://cdn.example/a"}]}}}""";
    [Fact] public async Task ResolvesAllPartsAndHonorsLinkedPage()
    {
        using var client = Client(_ => View);
        var catalog = Assert.Single(await client.ResolveAsync("https://www.bilibili.com/video/BV1xx411c7mD?p=2"));
        Assert.Equal(2, catalog.Items.Count); Assert.Equal(12, catalog.Items[0].Cid);
    }
    [Theory] [InlineData("bangumi", MediaKind.Bangumi)] [InlineData("cheese", MediaKind.Course)]
    public async Task ResolvesEpisodes(string type, MediaKind expected)
    {
        using var client = Client(_ => Episode);
        var catalog = Assert.Single(await client.ResolveAsync($"https://www.bilibili.com/{type}/play/ep1"));
        Assert.Equal(expected, Assert.Single(catalog.Items).Kind);
    }
    [Fact] public async Task AnonymousNavigationStillProvidesSigningKeys()
    {
        using var client = Client(r => r.RequestUri!.AbsolutePath.EndsWith("/view") ? View : r.RequestUri.AbsolutePath.EndsWith("/nav") ? Nav : Play);
        var item = (await client.ResolveAsync("BV1xx411c7mD"))[0].Items[0];
        var plan = await client.GetPlanAsync(item, 80);
        Assert.Equal(80, plan.Variant.Quality); Assert.NotNull(plan.Audio); Assert.Single(plan.Video);
    }
    [Theory] [InlineData("\"is_preview\":true", "试看")] [InlineData("\"is_drm\":1", "保护")]
    public async Task RefusesIncompleteOrProtectedSource(string field, string expected)
    {
        using var client = Client(r => r.RequestUri!.AbsolutePath.EndsWith("/view") ? View : r.RequestUri.AbsolutePath.EndsWith("/nav") ? Nav : "{\"code\":0,\"data\":{" + field + "}}");
        var item = (await client.ResolveAsync("BV1xx411c7mD"))[0].Items[0];
        var exception = await Assert.ThrowsAsync<BiliException>(() => client.GetPlanAsync(item, 80));
        Assert.Contains(expected, exception.Message);
    }
    private static BiliClient Client(Func<HttpRequestMessage, string> respond) => new(new Handler(respond));
    private sealed class Handler(Func<HttpRequestMessage, string> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(respond(request)) });
    }
}
