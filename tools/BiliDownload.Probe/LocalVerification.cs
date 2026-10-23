using BiliDownload.Core;
using System.Net;
using System.Net.Sockets;
using System.Text;

internal static class LocalVerification
{
    public static async Task<int> RunAsync(string directory, string ffmpeg)
    {
        using var stop = new CancellationTokenSource();
        var server = new TcpListener(IPAddress.Loopback, 0); server.Start();
        var port = ((IPEndPoint)server.LocalEndpoint).Port;
        var serving = Task.Run(async () => {
            try {
                while (!stop.IsCancellationRequested)
                {
                    using var client = await server.AcceptTcpClientAsync(stop.Token);
                    using var stream = client.GetStream(); using var reader = new StreamReader(stream, leaveOpen: true);
                    var line = await reader.ReadLineAsync(stop.Token); string? header;
                    while (!string.IsNullOrEmpty(header = await reader.ReadLineAsync(stop.Token))) { }
                    var name = line?.Contains("/audio") == true ? "audio.m4a" : "video.mp4";
                    var bytes = await File.ReadAllBytesAsync(Path.Combine(directory, name), stop.Token);
                    var response = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Length: {bytes.Length}\r\nETag: \"fixture-v1\"\r\nConnection: close\r\n\r\n");
                    await stream.WriteAsync(response, stop.Token); await stream.WriteAsync(bytes, stop.Token);
                }
            } catch (OperationCanceledException) { }
        });
        try
        {
            var store = new TaskStore(Path.Combine(directory, "state"));
            var media = new FixtureMedia(port);
            await using var queue = new DownloadQueue(media, store, Path.Combine(directory, "cache"), () => ffmpeg);
            var item = new MediaItem("video:fixture-" + Guid.NewGuid().ToString("N")[..8], "本地音视频测试", "测试分集", "", MediaKind.Video, 1, "BV1xx411c7mD", 1, 0, 1, "https://www.bilibili.com/");
            await queue.AddAsync([item], 80, Path.Combine(directory, "output"));
            await media.Started.Task.WaitAsync(TimeSpan.FromSeconds(10));
            var id = queue.Snapshot().First(t => t.Item.Id == item.Id).Id;
            await queue.SetStateAsync(id, DownloadState.Paused);
            if (queue.Snapshot().First(t => t.Id == id).State != DownloadState.Paused) throw new Exception("暂停状态不正确");
            await queue.SetStateAsync(id, DownloadState.Queued);
            for (var i = 0; i < 300; i++)
            {
                var task = queue.Snapshot().First(t => t.Id == id);
                if (task.State == DownloadState.Completed) { Console.WriteLine("本地下载、暂停继续、SQLite 持久化、FFmpeg 合并及最终保存成功：" + task.FilePath); return 0; }
                if (task.State == DownloadState.Failed) { Console.Error.WriteLine(task.Message); return 1; }
                await Task.Delay(100);
            }
            Console.Error.WriteLine("本地链路验证超时"); return 1;
        }
        finally { stop.Cancel(); server.Stop(); await serving; }
    }
    private sealed class FixtureMedia(int port) : IMediaService
    {
        private int calls;
        public TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public Task<IReadOnlyList<MediaCatalog>> ResolveAsync(string input, CancellationToken ct) => throw new NotSupportedException();
        public Task<IReadOnlyList<MediaVariant>> GetVariantsAsync(MediaItem item, CancellationToken ct) => throw new NotSupportedException();
        public async Task<DownloadPlan> GetPlanAsync(MediaItem item, int quality, CancellationToken ct)
        {
            if (Interlocked.Increment(ref calls) == 1) { Started.TrySetResult(); await Task.Delay(Timeout.Infinite, ct); }
            return new(new(80, "1080p", 1080, "H.264"), [new("video-fixture", $"http://127.0.0.1:{port}/video", [])], new("audio-fixture", $"http://127.0.0.1:{port}/audio", []));
        }
    }
}
