using System.Threading.Channels;

namespace BiliDownload.Core;

public sealed class DownloadQueue : IAsyncDisposable
{
    private readonly IMediaService media;
    private readonly TaskStore store;
    private readonly Func<string> ffmpeg;
    private readonly MediaDownloader downloader = new();
    private readonly string cache;
    private readonly object sync = new();
    private readonly Dictionary<Guid, DownloadTaskData> tasks = new();
    private readonly Dictionary<Guid, int> revisions = new();
    private readonly Dictionary<Guid, SemaphoreSlim> executionGates = new();
    private readonly Dictionary<Guid, CancellationTokenSource> running = new();
    private readonly Channel<(Guid Id, int Revision)> channel = Channel.CreateUnbounded<(Guid, int)>();
    private readonly CancellationTokenSource shutdown = new();
    private readonly Task[] workers;
    public event Action? Changed;

    public DownloadQueue(IMediaService media, TaskStore store, string cache, Func<string> ffmpeg)
    {
        this.media = media; this.store = store; this.cache = cache; this.ffmpeg = ffmpeg;
        foreach (var item in store.Load())
        {
            tasks[item.Id] = item.State is DownloadState.Completed or DownloadState.Canceled or DownloadState.Failed ? item :
                item with { State = DownloadState.Paused, Message = "上次下载已暂停，可继续" };
            revisions[item.Id] = 0;
            executionGates[item.Id] = new(1);
        }
        workers = Enumerable.Range(0, 2).Select(_ => Task.Run(WorkerAsync)).ToArray();
    }
    public IReadOnlyList<DownloadTaskData> Snapshot() { lock (sync) return tasks.Values.OrderByDescending(t => t.CreatedAt).ToArray(); }
    public async Task AddAsync(IEnumerable<MediaItem> items, int quality, string directory)
    {
        foreach (var item in items)
        {
            DownloadTaskData data;
            lock (sync)
            {
                if (tasks.Values.Any(t => t.Item.Id == item.Id && t.Quality == quality && t.State is not (DownloadState.Canceled or DownloadState.Failed))) continue;
                data = new(Guid.NewGuid(), item, quality, directory, CreatedAt: DateTimeOffset.UtcNow);
                tasks.Add(data.Id, data); revisions[data.Id] = 0; executionGates[data.Id] = new(1);
            }
            await store.SaveAsync(data); channel.Writer.TryWrite((data.Id, 0));
        }
        Changed?.Invoke();
    }
    public async Task SetStateAsync(Guid id, DownloadState state)
    {
        DownloadTaskData data; int revision;
        lock (sync)
        {
            var old = tasks[id];
            if (old.State == DownloadState.Completed) return;
            if (state == DownloadState.Queued && old.State is not (DownloadState.Paused or DownloadState.Failed)) return;
            revision = ++revisions[id];
            if (running.Remove(id, out var token)) token.Cancel();
            data = old with { State = state, FailureKind = null, Message = state switch { DownloadState.Paused => "已暂停", DownloadState.Canceled => "已取消", _ => "等待下载" } };
            tasks[id] = data;
        }
        await store.SaveAsync(data); Changed?.Invoke();
        if (state == DownloadState.Queued) channel.Writer.TryWrite((id, revision));
        if (state == DownloadState.Canceled)
        {
            SemaphoreSlim execution;
            lock (sync) execution = executionGates[id];
            await execution.WaitAsync();
            try
            {
                bool canceled; lock (sync) canceled = tasks[id].State == DownloadState.Canceled;
                var directory = Path.Combine(cache, id.ToString("N"));
                if (canceled && Directory.Exists(directory))
                    try { Directory.Delete(directory, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
            finally { execution.Release(); }
        }
    }
    private async Task UpdateAsync(Guid id, int revision, Func<DownloadTaskData, DownloadTaskData> update, bool persist = true)
    {
        DownloadTaskData data;
        lock (sync)
        {
            if (revisions[id] != revision) return;
            data = update(tasks[id]); tasks[id] = data;
            // Serialize persistent state transitions before a newer pause/resume can be persisted.
            if (persist) store.SaveAsync(data).GetAwaiter().GetResult();
        }
        Changed?.Invoke(); await Task.CompletedTask;
    }
    private async Task WorkerAsync()
    {
        try
        {
            await foreach (var (id, revision) in channel.Reader.ReadAllAsync(shutdown.Token))
            {
                SemaphoreSlim execution;
                lock (sync) execution = executionGates[id];
                await execution.WaitAsync(shutdown.Token);
                try
                {
                DownloadTaskData item; CancellationTokenSource token;
                lock (sync)
                {
                    if (revisions[id] != revision || tasks[id].State != DownloadState.Queued) continue;
                    item = tasks[id]; token = CancellationTokenSource.CreateLinkedTokenSource(shutdown.Token); running[id] = token;
                }
                using (token)
                {
                    try
                    {
                        var ct = token.Token;
                        var executable = ffmpeg();
                        await MediaDownloader.CheckFfmpegAsync(executable, ct);
                        Directory.CreateDirectory(item.Directory);
                        var work = Path.Combine(cache, id.ToString("N")); Directory.CreateDirectory(work);
                        await UpdateAsync(id, revision, t => t with { State = DownloadState.Downloading, Message = "获取媒体源" });
                        DownloadPlan? plan = null; List<string> videoFiles = []; string? audioFile = null;
                        for (var attempt = 0; attempt < 3; attempt++)
                        {
                            try
                            {
                                plan = await media.GetPlanAsync(item.Item, item.Quality, ct);
                                var parts = plan.Video.Concat(plan.Audio is { } a ? [a] : []).ToArray();
                                videoFiles = []; audioFile = null;
                                for (var p = 0; p < parts.Length; p++)
                                {
                                    var index = p; var part = parts[p];
                                    var file = Path.Combine(work, LinkParser.SafeName(part.Key) + ".part");
                                    var last = DateTime.UtcNow.AddSeconds(-2);
                                    await downloader.DownloadPartAsync(part, file, fraction =>
                                    {
                                        if ((DateTime.UtcNow - last).TotalMilliseconds < 700 && fraction < 1) return;
                                        last = DateTime.UtcNow;
                                        UpdateAsync(id, revision, t => t with { Progress = (index + fraction) / parts.Length * 95,
                                            Message = $"正在下载 · {plan.Variant.Label}" }, false).GetAwaiter().GetResult();
                                    }, ct);
                                    if (p < plan.Video.Count) videoFiles.Add(file); else audioFile = file;
                                }
                                break;
                            }
                            catch (Exception ex) when (attempt < 2 && (ex is HttpRequestException || ex is OperationCanceledException && !ct.IsCancellationRequested))
                            {
                                await UpdateAsync(id, revision, t => t with { Message = "连接中断，重新获取地址后重试" });
                                await Task.Delay(TimeSpan.FromSeconds(2 << attempt), ct);
                            }
                        }
                        ct.ThrowIfCancellationRequested();
                        await UpdateAsync(id, revision, t => t with { State = DownloadState.Merging, Progress = 96, Message = "合并音视频" });
                        var temporary = Path.Combine(work, "merged.mp4");
                        await MediaDownloader.MergeAsync(executable, videoFiles, audioFile, temporary, ct);
                        ct.ThrowIfCancellationRequested();
                        var filename = LinkParser.SafeName($"{item.Item.Title} P{item.Item.Index} {item.Item.PartTitle}") +
                            $" [{item.Item.Id.Replace(':', '-')}-{plan!.Variant.Quality}].mp4";
                        var destination = Path.Combine(item.Directory, filename);
                        if (File.Exists(destination)) destination = Path.Combine(item.Directory, Path.GetFileNameWithoutExtension(filename) + $"-{id.ToString("N")[..6]}.mp4");
                        var staging = Path.Combine(item.Directory, $".bilidownload-{id:N}.tmp");
                        try
                        {
                            await using (var source = File.OpenRead(temporary))
                            await using (var target = new FileStream(staging, FileMode.Create, FileAccess.Write, FileShare.None, 65536, true))
                                await source.CopyToAsync(target, ct);
                            await UpdateAsync(id, revision, t => {
                                ct.ThrowIfCancellationRequested();
                                File.Move(staging, destination, false);
                                return t with { State = DownloadState.Completed, Progress = 100, Message = "下载完成", FilePath = destination };
                            });
                        }
                        finally { if (File.Exists(staging)) File.Delete(staging); }
                        ct.ThrowIfCancellationRequested();
                        // Only this task's generated cache is removed; user output is never deleted.
                        try { Directory.Delete(work, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
                    }
                    catch (OperationCanceledException) when (token.IsCancellationRequested) { }
                    catch (Exception ex)
                    {
                        var message = ex is BiliException ? ex.Message : ex is IOException ? "文件写入失败，请检查空间和目录权限。" :
                            ex is HttpRequestException ? "网络请求失败，可稍后重试。" : "下载失败，请重试或检查源视频是否仍可播放。";
                        await UpdateAsync(id, revision, t => t with { State = DownloadState.Failed, Message = message, FailureKind = (ex as BiliException)?.Kind });
                    }
                    finally { lock (sync) { if (revisions[id] == revision) running.Remove(id); } }
                }
                }
                finally { execution.Release(); }
            }
        }
        catch (OperationCanceledException) when (shutdown.IsCancellationRequested) { }
    }
    public async ValueTask DisposeAsync()
    {
        shutdown.Cancel(); channel.Writer.TryComplete();
        await Task.WhenAll(workers);
        foreach (var item in Snapshot().Where(t => t.State is DownloadState.Downloading or DownloadState.Merging or DownloadState.Queued))
            await store.SaveAsync(item with { State = DownloadState.Paused, Message = "上次下载已暂停，可继续" });
        downloader.Dispose(); shutdown.Dispose();
    }
}
