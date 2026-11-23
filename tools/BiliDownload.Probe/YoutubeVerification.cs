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
        var item = catalogs.Single().Items.Single();
        var variants = catalogs.Single().InitialVariants!;
        var variant = variants.Where(v => v.Height <= int.Parse(args[5])).MaxBy(v => v.Height)!;
        Console.WriteLine($"Resolved {item.Id}: {variant.Label}, format {variant.FormatId}, {variant.EstimatedBytes} bytes");
        var queue = new DownloadQueue(media, new TaskStore(root), Path.Combine(root, "cache"), () => Path.Combine(app, "tools", "ffmpeg.exe"));
        await using (queue)
        {
            await queue.AddAsync([item], variant.Quality, Path.Combine(root, "output"), new Dictionary<string,string> { [item.Id] = FileNaming.Render(item) }, variant.FormatId);
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
