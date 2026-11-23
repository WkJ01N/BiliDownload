using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using BiliDownload.Core;

internal static class BiliAudioVerification
{
    private sealed class Api(int port) : HttpMessageHandler
    {
        public bool RemoveTrack;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            object payload = request.RequestUri!.AbsolutePath switch {
                "/x/web-interface/view" => new { aid = 1, bvid = "BV1xx411c7mD", title = "音频测试", pic = "", pages = new[]{ new {cid=1,part="上"}, new {cid=2,part="下"} } },
                "/x/web-interface/nav" => new { isLogin = false, wbi_img = new { img_url = "https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png", sub_url = "https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png" } },
                _ => new { dash = new { duration = 1, video = new[] { new {id=80,codecid=7,height=1080,codecs="avc1",baseUrl=$"http://127.0.0.1:{port}/video"} },
                    audio = new[] { new { id=RemoveTrack?2:1, codecs="mp4a.40.2", bandwidth=128000, baseUrl=$"http://127.0.0.1:{port}/audio" } } } }
            };
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(new {code=0,data=payload})) });
        }
    }

    public static async Task Run(string tool,string inputs,string work)
    {
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(40));
        using var tcp = new TcpListener(IPAddress.Loopback,0); tcp.Start();
        var api = new Api(((IPEndPoint)tcp.LocalEndpoint).Port);
        var audioRequests = 0;
        var serving = Task.Run(async () => {
            while(!stop.IsCancellationRequested) {
                using var client = await tcp.AcceptTcpClientAsync(stop.Token);
                using var socket = client.GetStream(); using var reader = new StreamReader(socket,leaveOpen:true);
                var line = await reader.ReadLineAsync(stop.Token);
                if(line?.StartsWith("GET /audio ") != true) throw new Exception("Requested video during audio-only transfer");
                while(!string.IsNullOrEmpty(await reader.ReadLineAsync(stop.Token))) { }
                Interlocked.Increment(ref audioRequests);
                var bytes=await File.ReadAllBytesAsync(Path.Combine(inputs,"aac.m4a"),stop.Token);
                await socket.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\n\r\n"),stop.Token);
                await socket.WriteAsync(bytes,stop.Token);
            }
        });
        try {
            using var bili = new BiliClient(api);
            var media = new MediaRouter(bili,new YoutubeEngine(()=>null,"",()=>null),()=>false);
            var catalog = (await media.ResolveAsync("https://www.bilibili.com/video/BV1xx411c7mD?p=2",stop.Token)).Single();
            var item = catalog.Items.Single(i=>i.Id == catalog.TargetItemId);
            if(item.Cid != 2) throw new Exception("Linked part mismatch");
            var track = (await media.AudioAsync(item,stop.Token)).Single();
            var root = Path.Combine(work,Guid.NewGuid().ToString("N")); var store = new TaskStore(root);
            await using var queue = new DownloadQueue(media,store,Path.Combine(root,"cache"),()=>tool);
            foreach(var rate in new[]{0,128,128}) {
                var output = new AudioOutput(track,rate);
                await queue.AddAsync([item],0,root,new Dictionary<string,string>{{item.Id,"测试"+output.Extension}},track.Id,output);
            }
            while(queue.Snapshot().Any(t=>t.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Merging)) await Task.Delay(50,stop.Token);
            var tasks = store.Load();
            if(tasks.Count != 2 || tasks.Any(t=>t.State != DownloadState.Completed || !File.Exists(t.FilePath)) || audioRequests != 2) throw new Exception("Audio queue selection or deduplication failed");
            api.RemoveTrack = true;
            try { await media.AudioPlanAsync(item,new(track),stop.Token); throw new Exception("Accepted stale track"); }
            catch(BiliException e) when(e.Kind == BiliErrorKind.TrackChanged) { }
            Console.WriteLine("PASS Bilibili P2 audio queue, separate encodings, exact track retry, disk snapshots, zero video requests");
        } finally {
            stop.Cancel(); tcp.Stop();
            try { await serving; } catch(OperationCanceledException) { }
        }
    }
}
