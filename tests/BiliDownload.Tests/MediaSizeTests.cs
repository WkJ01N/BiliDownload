using System.Text.Json;
using BiliDownload.Core;
using Xunit;
namespace BiliDownload.Tests;
public class MediaSizeTests {
    [Fact] public void IncludesSelectedVideoAndHighestAac() {
        using var doc = JsonDocument.Parse("""{"dash":{"duration":100,"video":[{"id":80,"height":1080,"codecid":7,"bandwidth":800000},{"id":64,"height":720,"codecid":7,"bandwidth":400000}],"audio":[{"codecs":"mp4a.40.2","bandwidth":128000},{"codecs":"mp4a.40.2","bandwidth":192000},{"codecs":"flac","bandwidth":900000}]}}""");
        var variants = BiliClient.Variants(doc.RootElement);
        Assert.Equal(12400000L, variants.Single(v => v.Quality == 80).EstimatedBytes);
        Assert.Equal(7400000L, variants.Single(v => v.Quality == 64).EstimatedBytes);
    }
    [Fact] public void MissingMetadataIsUnknown() {
        using var doc = JsonDocument.Parse("""{"dash":{"video":[{"id":80,"codecid":7}]}}""");
        Assert.Null(Assert.Single(BiliClient.Variants(doc.RootElement)).EstimatedBytes);
    }
    [Fact] public void ProgressiveSumsAllParts() {
        using var doc = JsonDocument.Parse("""{"quality":80,"durl":[{"size":100},{"size":200}]}""");
        Assert.Equal(300L, Assert.Single(BiliClient.Variants(doc.RootElement)).EstimatedBytes);
    }
    [Fact] public void OldSettingsStillAsk() {
        var settings = JsonSerializer.Deserialize<AppSettings>("""{"DownloadDirectory":"C:/Videos"}""");
        Assert.Equal("Ask", settings!.CloseBehavior);
    }
}
