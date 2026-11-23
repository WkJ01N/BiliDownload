using BiliDownload.Core;
using System.Text.Json;
using Xunit;

namespace BiliDownload.Tests;
public sealed class AudioTests
{
    [Fact] public void SourceTracksAreRankedAndOnlyContainAudioUrls() {
        using var json = JsonDocument.Parse("""{"dash":{"duration":12,"video":[{"baseUrl":"video"}],"audio":[{"id":1,"codecs":"mp4a.40.2","bandwidth":128000,"baseUrl":"aac"}],"dolby":{"audio":[{"id":2,"codecs":"ec-3","baseUrl":"dolby"}]},"flac":{"audio":{"id":3,"codecs":"fLaC","baseUrl":"lossless"}}}}""");
        var rows = AudioMedia.BiliTracks(json.RootElement, 57);
        Assert.Equal(new[]{"FLAC","E-AC-3","AAC"}, rows.Select(r=>r.Track.Codec));
        Assert.Equal(new[]{"lossless","dolby","aac"}, rows.SelectMany(r=>r.Parts).Select(p=>p.Url));
        Assert.Equal(192000L,rows[2].Track.DownloadBytes);
        foreach (int rate in new[]{128,192,320}) Assert.Equal(rate*1500L,new AudioOutput(rows[0].Track,rate).EstimatedBytes);
    }
    [Fact] public void MixedRetainsFullTransferAndUnknownAudioSize() {
        using var data = JsonDocument.Parse("""{"quality":64,"durl":[{"url":"a","size":120},{"url":"b","size":90}]}""");
        var (track,parts) = Assert.Single(AudioMedia.BiliTracks(data.RootElement,2));
        Assert.True(track.Mixed); Assert.Equal(2,parts.Length); Assert.Equal(210L,track.DownloadBytes); Assert.Null(track.AudioBytes);
    }
    [Fact] public void MusicLinkSurvivesClipboardAndNamesKeepExtension() {
        var url = "https://music.youtube.com/watch?v=jNQXAC9IVRw&list=PL123";
        Assert.Equal(url,Assert.Single(ClipboardPolicy.Links(url)));
        Assert.True(YoutubeLink.Parse(new Uri(url))!.Music);
        Assert.Equal("试听.opus",FileNaming.Normalize("试听.opus",".opus"));
        var temp = Directory.CreateTempSubdirectory();
        try { File.WriteAllText(Path.Combine(temp.FullName,"试听.opus"),"first");
            var staging = Path.Combine(temp.FullName,"temp"); File.WriteAllText(staging,"second");
            Assert.Equal("试听 (1).opus",Path.GetFileName(FileNaming.MoveUnique(staging,temp.FullName,"试听.opus"))); }
        finally { temp.Delete(true); }
    }
}
