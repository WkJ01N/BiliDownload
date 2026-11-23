using BiliDownload.Core;
using System.Net;
using System.Text.Json;

internal static class YoutubeVerification
{
    private sealed class ArchiveHandler(string archive) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(File.OpenRead(archive)) });
    }

    public static async Task<int> RunAsync(string[] args)
    {
        string app = Path.GetFullPath(args[1]), archive = Path.GetFullPath(args[2]);
        string root = Path.GetFullPath(args[3]);
        Directory.CreateDirectory(root);
        var manifest = JsonSerializer.Deserialize<ComponentPackage[]>(File.ReadAllText(Path.Combine(app, "Assets", "components.json")))!;
        using var http = new HttpClient(new ArchiveHandler(archive));
        var package = manifest.Single(p => p.Id == "youtube");
        var installer = new ComponentInstaller(Path.Combine(root, "components"), http);
        var python = await installer.InstallAsync(package);
        var engine = new YoutubeEngine(() => python, Path.Combine(app, "Assets", "youtube_bridge.py"), () => null);
        using var bili = new BiliClient();
        var media = new MediaRouter(bili, engine, () => false);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(6));
        var catalogs = await media.ResolveAsync(args[4], timeout.Token);
        var catalog = catalogs.Single();
        var item = catalog.Items.First(i => catalog.TargetItemId == null || i.Id == catalog.TargetItemId);
        var isAudio = args[0] == "--verify-audio";
        var tool = isAudio ? Path.GetFullPath(args[6]) : Path.Combine(app, "tools", "ffmpeg.exe");
        var queue = new DownloadQueue(media, new TaskStore(root), Path.Combine(root, "cache"), () => tool);
        await using (queue)
        {
            if(isAudio) {
                var tracks = await media.AudioAsync(item,timeout.Token);
                var track = args.Length > 7 ? tracks.First(t => t.Codec.Equals(args[7],StringComparison.OrdinalIgnoreCase)) : tracks.First();
                var selection = new AudioOutput(track,int.Parse(args[5]));
                var name = FileNaming.Render(item,extension:selection.Extension);
                Console.WriteLine($"Audio {item.Id}: {track.Codec}, {track.Bitrate} kb/s, transfer={track.DownloadBytes}, mixed={track.Mixed}");
                await queue.AddAsync([item],0,Path.Combine(root,"output"), new Dictionary<string,string> { [item.Id] = name },track.Id,selection);
            } else {
                var variants = catalogs.Single().InitialVariants!;
                var variant = variants.Where(v => v.Height <= int.Parse(args[5])).MaxBy(v => v.Height)!;
                Console.WriteLine($"Resolved {item.Id}: {variant.Label}, format {variant.FormatId}, {variant.EstimatedBytes} bytes");
                await queue.AddAsync([item],variant.Quality,Path.Combine(root,"output"),new Dictionary<string,string> { [item.Id] = FileNaming.Render(item) }, variant.FormatId);
            }
            while (true)
            {
                var task = queue.Snapshot().First(t => t.Item.Id == item.Id);
                if (task.State == DownloadState.Failed) throw new Exception(task.Message);
                if (task.State == DownloadState.Completed) { Console.WriteLine($"Completed {new FileInfo(task.FilePath!).Length} bytes: {task.FinalFileName}"); break; }
                await Task.Delay(200, timeout.Token);
            }
        }
        return 0;
    }
}
