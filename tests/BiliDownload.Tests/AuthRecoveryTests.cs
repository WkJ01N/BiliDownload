using BiliDownload.Core;
using System.Net;
using System.Text.Json;
using Xunit;

namespace BiliDownload.Tests;

public sealed class AuthRecoveryTests
{
    [Fact] public void CanceledLoginKeepsOriginalNotice()
    {
        Assert.Null(AuthRecovery.LoginMessage(new(LoginOutcome.Canceled)));
        Assert.Null(AuthRecovery.LoginMessage(new(LoginOutcome.Canceled), forTask: true));
    }
    [Fact] public void SuccessfulLoginOffersExplicitRecovery()
    {
        Assert.Contains("重新解析", AuthRecovery.LoginMessage(new(LoginOutcome.Success)));
        Assert.Contains("继续 / 重试", AuthRecovery.LoginMessage(new(LoginOutcome.Success), forTask: true));
        Assert.Equal("验证失败", AuthRecovery.LoginMessage(new(LoginOutcome.Failed, "验证失败")));
    }
    [Theory]
    [InlineData(SessionState.Guest, true)] [InlineData(SessionState.Saved, true)]
    [InlineData(SessionState.Authenticated, false)] [InlineData(SessionState.Expired, true)]
    public void RestrictedRequestOffersLoginAccordingToAccountState(SessionState state, bool expected) =>
        Assert.Equal(expected, AuthRecovery.NeedsLogin(BiliErrorKind.RequestRestricted, state));

    [Fact] public async Task Http412RetainsAuthenticatedSessionAndHttpCode()
    {
        using var client = new BiliClient(new Handler(HttpStatusCode.PreconditionFailed, "")); client.ConfirmLogin();
        var error = await Assert.ThrowsAsync<BiliException>(() => client.ApiAsync("https://api.bilibili.com/test"));
        Assert.Equal(BiliErrorKind.RequestRestricted, error.Kind); Assert.Equal(412, error.HttpStatus);
        Assert.Equal(SessionState.Authenticated, client.SessionState); Assert.Null(error.ApiCode);
        Assert.False(AuthRecovery.NeedsLogin(error.Kind, client.SessionState));
    }
    [Theory] [InlineData(-412, BiliErrorKind.RequestRestricted)] [InlineData(-352, BiliErrorKind.RequestRestricted)] [InlineData(-101, BiliErrorKind.LoginRequired)]
    public async Task ApiErrorsAreStructured(int code, BiliErrorKind kind)
    {
        using var client = new BiliClient(new Handler(HttpStatusCode.OK, $"{{\"code\":{code},\"message\":\"test\"}}"));
        var error = await Assert.ThrowsAsync<BiliException>(() => client.ApiAsync("https://api.bilibili.com/test"));
        Assert.Equal(kind, error.Kind); Assert.Equal(code, error.ApiCode); Assert.Null(error.HttpStatus);
    }
    [Fact] public async Task SuccessfulLoginDoesNotPreventLaterRestriction()
    {
        using var client = new BiliClient(new Handler(HttpStatusCode.PreconditionFailed, ""));
        client.RestoreSession(); Assert.Equal(SessionState.Saved, client.SessionState);
        client.ConfirmLogin();
        await Assert.ThrowsAsync<BiliException>(() => client.ApiAsync("https://api.bilibili.com/test"));
        Assert.Equal(SessionState.Authenticated, client.SessionState);
        client.ClearSession(); Assert.Equal(SessionState.Guest, client.SessionState);
    }
    [Fact] public async Task GuestHttp412OffersLoginAndDoesNotCreateSession()
    {
        using var client = new BiliClient(new Handler(HttpStatusCode.PreconditionFailed, ""));
        var error = await Assert.ThrowsAsync<BiliException>(() => client.ApiAsync("https://api.bilibili.com/test"));
        Assert.True(AuthRecovery.NeedsLogin(error.Kind, client.SessionState));
        Assert.Equal(SessionState.Guest, client.SessionState);
    }
    [Fact] public async Task ExpiredSessionOffersLogin()
    {
        using var client = new BiliClient(new Handler(HttpStatusCode.OK, "{\"code\":-101,\"message\":\"expired\"}"));
        client.ConfirmLogin();
        var error = await Assert.ThrowsAsync<BiliException>(() => client.ApiAsync("https://api.bilibili.com/test"));
        Assert.Equal(SessionState.Expired, client.SessionState);
        Assert.True(AuthRecovery.NeedsLogin(error.Kind, client.SessionState));
    }
    [Fact] public async Task MediaSource412PreservesStructuredRecoveryError()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-412-" + Guid.NewGuid());
        try {
            using var downloader = new MediaDownloader(new Handler(HttpStatusCode.PreconditionFailed, ""));
            var error = await Assert.ThrowsAsync<BiliException>(() => downloader.DownloadPartAsync(
                new("video", "https://example.com/video", ["https://example.com/backup"]), Path.Combine(root, "video.part"), _ => { }, default));
            Assert.Equal(BiliErrorKind.RequestRestricted, error.Kind); Assert.Equal(412, error.HttpStatus);
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Fact] public void OldTaskJsonRemainsReadable()
    {
        var item = new MediaItem("v", "title", "part", "", MediaKind.Video, 1, "", 1, 0, 1, "");
        var record = new DownloadTaskData(Guid.NewGuid(), item, 80, "");
        var json = JsonSerializer.SerializeToNode(record)!.AsObject(); json.Remove("FailureKind");
        Assert.Null(JsonSerializer.Deserialize<DownloadTaskData>(json.ToJsonString())!.FailureKind);
    }
    [Fact] public void ToolResolverKeepsValidExternalPathAndRecoversOldBundledPath()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-tools-" + Guid.NewGuid());
        Directory.CreateDirectory(Path.Combine(root, "tools"));
        try {
            var bundled = Path.Combine(root, "tools", "ffmpeg.exe"); File.WriteAllText(bundled, "fixture");
            var external = Path.Combine(root, "custom.exe"); File.WriteAllText(external, "fixture");
            Assert.Equal(external, ToolPathResolver.Resolve(external, root));
            Assert.Equal(bundled, ToolPathResolver.Resolve(Path.Combine(root, "old", "ffmpeg.exe"), root));
            Assert.Equal("custom-ffmpeg", ToolPathResolver.Resolve("custom-ffmpeg", root));
        } finally { Directory.Delete(root, true); }
    }
    private sealed class Handler(HttpStatusCode status, string body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body) });
    }
}
