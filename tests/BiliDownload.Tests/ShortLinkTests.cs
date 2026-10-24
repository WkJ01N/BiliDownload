using System.Net;
using BiliDownload.Core;
using Xunit;
namespace BiliDownload.Tests;
public class ShortLinkTests {
    private const string View = """{"code":0,"data":{"aid":1,"bvid":"BV1xx411c7mD","title":"测试","pages":[{"cid":1,"part":"一"},{"cid":2,"part":"二"}]}}""";
    private class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond) : HttpMessageHandler {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => respond(request,ct);
    }
    private static HttpResponseMessage Redirect(string target) { var r = new HttpResponseMessage(HttpStatusCode.Redirect); r.Headers.Location = new(target,UriKind.RelativeOrAbsolute); return r; }
    [Fact] public async Task SuccessfulTargetsAreCachedAndKeepPage() {
        var shortCalls = 0;
        using var client = new BiliClient(new Handler((r,ct) => Task.FromResult(r.RequestUri!.Host == "b23.tv" ? (++shortCalls > 0 ? Redirect("https://www.bilibili.com/video/BV1xx411c7mD?p=2") : throw new Exception()) : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(View) })));
        for(var i=0;i<2;i++) Assert.Equal("video:2",Assert.Single(await client.ResolveAsync("https://b23.tv/target")).TargetItemId);
        Assert.Equal(1,shortCalls);
    }
    [Theory] [InlineData("https://example.com/video/BV1xx411c7mD")] [InlineData("https://b23.tv/loop")]
    public async Task RejectsOutsideHostsAndCycles(string target) {
        var calls = 0;
        using var client = new BiliClient(new Handler((r,ct) => { calls++; return Task.FromResult(Redirect(target)); }));
        await Assert.ThrowsAsync<BiliException>(() => client.ResolveAsync("https://b23.tv/loop")); Assert.Equal(1,calls);
    }
    [Fact] public async Task FollowsRelativeRedirectAndDoesNotCacheFailures() {
        var calls=0;
        using var client = new BiliClient(new Handler((r,ct) => {
            if(r.RequestUri!.Host != "b23.tv") return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content=new StringContent(View) });
            calls++;
            return Task.FromResult(calls == 1 ? new HttpResponseMessage(HttpStatusCode.PreconditionFailed) : Redirect(r.RequestUri.AbsolutePath == "/start" ? "/end" : "https://www.bilibili.com/video/BV1xx411c7mD?p=2"));
        }));
        await Assert.ThrowsAsync<BiliException>(() => client.ResolveAsync("https://b23.tv/start"));
        Assert.Equal("video:2",Assert.Single(await client.ResolveAsync("https://b23.tv/start")).TargetItemId); Assert.Equal(3,calls);
    }
    [Fact] public async Task CancellationStopsNetworkWait() {
        using var client = new BiliClient(new Handler(async(r,ct) => { await Task.Delay(Timeout.Infinite,ct); throw new Exception(); }));
        using var cancel = new CancellationTokenSource(30);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.ResolveAsync("https://b23.tv/slow",cancel.Token));
    }
    [Fact] public async Task ShortRequestHasIndependentDeadline() {
        using var client = new BiliClient(new Handler(async(r,ct) => { await Task.Delay(Timeout.Infinite,ct); throw new Exception(); }));
        var error = await Assert.ThrowsAsync<BiliException>(() => client.ResolveAsync("https://b23.tv/slow"));
        Assert.Contains("超时",error.Message);
    }
    [Fact] public async Task SigningKeysAreReusedAndInvalidatedAfterRejection() {
        var navCalls = 0;
        using var client = new BiliClient(new Handler((r,ct) => {
            var path = r.RequestUri!.AbsolutePath;
            var content = path == "/reject" ? """{"code":-403,"message":"invalid signature"}""" : path.EndsWith("/nav") ? """{"code":0,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}}""" : """{"code":0,"data":{"dash":{"video":[{"id":80,"codecid":7}]}}}""";
            if(path.EndsWith("/nav")) navCalls++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content=new StringContent(content) });
        }));
        var item = new MediaItem("video:1","title","part","",MediaKind.Video,1,"BV1xx411c7mD",1,0,1,"");
        await client.GetVariantsAsync(item); await client.GetVariantsAsync(item); Assert.Equal(1,navCalls);
        await Assert.ThrowsAsync<BiliException>(()=>client.ApiAsync("https://api.bilibili.com/reject"));
        await client.GetVariantsAsync(item); Assert.Equal(2,navCalls);
    }
}
