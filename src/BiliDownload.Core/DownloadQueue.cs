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
    private readonly Dictionary<Guid,int> waitingForAttachments = new();
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
    public async Task AddAsync(IEnumerable<MediaItem> items, int quality, string directory, IReadOnlyDictionary<string, string>? fileNames = null, string? formatId = null, AudioOutput? audio = null)
    {
        foreach (var item in items)
        {
            DownloadTaskData data;
            lock (sync)
            {
                if (tasks.Values.Any(t => t.Item.X?.OutputExtension == item.X?.OutputExtension && (t.Item.YoutubeId ?? t.Item.Id) == (item.YoutubeId ?? item.Id) && t.Quality == quality && t.AudioOutput?.Track.Id == audio?.Track.Id && t.AudioOutput?.Mp3Kbps == audio?.Mp3Kbps && t.State is not (DownloadState.Canceled or DownloadState.Failed))) continue;
                data = new(Guid.NewGuid(), item, quality, directory, CreatedAt: DateTimeOffset.UtcNow, OutputFileName: fileNames != null && fileNames.TryGetValue(item.Id, out var name) ? FileNaming.Normalize(name, audio?.Extension ?? item.X?.OutputExtension ?? ".mp4") : null, FormatId: formatId, AudioOutput: audio);
                tasks.Add(data.Id, data); revisions[data.Id] = 0; executionGates[data.Id] = new(1);
                store.SaveAsync(data).GetAwaiter().GetResult();
            }
            channel.Writer.TryWrite((data.Id, 0));
        }
        Changed?.Invoke();
        await Task.CompletedTask;
    }
    public async Task SetStateAsync(Guid id, DownloadState state)
    {
        DownloadTaskData data; int revision;
        lock (sync)
        {
            if (!tasks.TryGetValue(id, out var old)) return;
            if (old.State == DownloadState.Completed) return;
            if (state == DownloadState.Queued && old.State is not (DownloadState.Paused or DownloadState.Failed)) return;
            revision = ++revisions[id];
            if (running.Remove(id, out var token)) token.Cancel();
            data = old with { State = state, FailureKind = null, Message = state switch { DownloadState.Paused => "已暂停", DownloadState.Canceled => "已取消", _ => "等待下载" } };
            tasks[id] = data; waitingForAttachments.Remove(id);
            store.SaveAsync(data).GetAwaiter().GetResult();
            if (state != DownloadState.Queued) WakeDocuments(id);
        }
        Changed?.Invoke();
        if (state == DownloadState.Queued) channel.Writer.TryWrite((id, revision));
        if (state == DownloadState.Canceled)
        {
            SemaphoreSlim execution;
            lock (sync) execution = executionGates[id];
            await execution.WaitAsync();
            try
            {
                bool canceled; lock (sync) canceled = tasks.TryGetValue(id, out var current) && current.State == DownloadState.Canceled;
                var directory = Path.Combine(cache, id.ToString("N"));
                if (canceled && Directory.Exists(directory))
                    try { Directory.Delete(directory, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
            finally { execution.Release(); }
        }
    }
    // Called under sync when an attachment settles. Waiting documents do not occupy a worker.
    private void WakeDocuments(Guid attachment)
    {
        foreach (var (id, revision) in waitingForAttachments.ToArray()) {
            if (tasks.TryGetValue(id, out var doc) && doc.Item.X?.Related?.Contains(attachment.ToString()) == true) {
                waitingForAttachments.Remove(id);
                channel.Writer.TryWrite((id, revision));
            }
        }
    }
    private async Task UpdateAsync(Guid id, int revision, Func<DownloadTaskData, DownloadTaskData> update, bool persist = true)
    {
        DownloadTaskData data;
        lock (sync)
        {
            if (revisions[id] != revision || !tasks.ContainsKey(id)) return;
            data = update(tasks[id]); tasks[id] = data;
            // Serialize persistent state transitions before a newer pause/resume can be persisted.
            if (persist) store.SaveAsync(data).GetAwaiter().GetResult();
            if (data.State is DownloadState.Completed or DownloadState.Failed) WakeDocuments(id);
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
                    if (revisions[id] != revision || !tasks.ContainsKey(id) || tasks[id].State != DownloadState.Queued) continue;
                    item = tasks[id];
                    if (item.Item.X?.Related is { Length: > 0 } dependencies && dependencies.Any(key =>
                        Guid.TryParse(key,out var dependency) && tasks.TryGetValue(dependency,out var task) &&
                        task.State is DownloadState.Downloading or DownloadState.Merging or DownloadState.Queued)) {
                        waitingForAttachments[id] = revision;
                        continue;
                    }
                    token = CancellationTokenSource.CreateLinkedTokenSource(shutdown.Token); running[id] = token;
                }
                using (token)
                {
                    try
                    {
                        var ct = token.Token;
                        var related = item.Item.X?.Related ?? [];
                        var executable = item.Item.X != null && item.AudioOutput == null && !item.Item.X.ExportGif ? "" : ffmpeg();
                        if (item.AudioOutput is { } output) await AudioExporter.CheckAsync(executable, output, ct);
                        else if (item.Item.X?.ExportGif == true) await AudioExporter.CheckGifAsync(executable, ct);
                        else if (item.Item.X == null) await MediaDownloader.CheckFfmpegAsync(executable, ct);
                        Directory.CreateDirectory(item.Directory);
                        var work = Path.Combine(cache, id.ToString("N")); Directory.CreateDirectory(work);
                        await UpdateAsync(id, revision, t => t with { State = DownloadState.Downloading, Message = "获取媒体源" });
                        var actualQuality = item.Quality;
                        IReadOnlyList<string> videoFiles = []; string? audioFile = null;
                        var temporary = Path.Combine(work, "merged" + (item.AudioOutput?.Extension ?? item.Item.X?.OutputExtension ?? ".mp4"));
                        if (item.Item.X is {} x) {
                            if (x.Kind == "markdown") {
                                var markdown = x.Markdown ?? throw new BiliException("帖子正文不可用，请重新解析。");
                                var linked = Snapshot().Where(t => related.Contains(t.Id.ToString()) && t.State == DownloadState.Completed &&
                                    t.Directory == item.Directory && t.FilePath != null && File.Exists(t.FilePath)).OrderBy(t => t.Item.Index);
                                foreach (var t in linked) {
                                    var name = Path.GetFileName(t.FilePath!);
                                    var title = name.Replace("\\","\\\\").Replace("[","\\[").Replace("]","\\]");
                                    var embed = t.Item.X?.Kind == "image" || t.Item.X?.ExportGif == true ? "!" : "";
                                    markdown += $"\n{embed}[{title}]({Uri.EscapeDataString(name)})\n";
                                }
                                await File.WriteAllTextAsync(temporary, markdown, ct);
                            } else {
                                if (media is not MediaRouter router) throw YoutubeEngine.XFailure("Engine");
                                var file = await router.FetchXAsync(item,work, fraction => UpdateAsync(id,revision,t => t with {
                                    Progress = fraction * 95, Message = "正在下载 X 附件" }, false).GetAwaiter().GetResult(),ct);
                                if (item.AudioOutput != null) videoFiles = [file];
                                else if (x.ExportGif) {
                                    await UpdateAsync(id,revision,t => t with { State = DownloadState.Merging, Message = "转换 GIF" });
                                    await MediaDownloader.RunAsync(executable, ["-hide_banner","-loglevel","error","-nostdin","-y","-protocol_whitelist","file","-i",file,
                                        "-filter_complex","fps=15,scale='min(480,iw)':-2:flags=lanczos,split[v][p];[p]palettegen[pal];[v][pal]paletteuse","-loop","0",temporary],ct);
                                } else temporary = file;
                            }
                        }
                        else if (item.Item.YoutubeId != null)
                        {
                            if (media is not MediaRouter router) throw new BiliException("请安装 YouTube 下载组件。", BiliErrorKind.YoutubeComponent);
                            var tracks = await router.FetchAsync(item, work, fraction => UpdateAsync(id, revision, t => t with {
                                Progress = fraction * 95, Message = item.AudioOutput == null ? "正在下载 YouTube 视频" : "正在下载音轨" }, false).GetAwaiter().GetResult(), ct);
                            videoFiles = tracks.Video; audioFile = tracks.Audio; actualQuality = tracks.Quality;
                            await UpdateAsync(id, revision, t => t with { FormatId = tracks.Format });
                        }
                        else
                        for (var attempt = 0; attempt < 3; attempt++)
                        {
                            try
                            {
                                var plan = item.AudioOutput is { } audio
                                    ? await (media as IAudioPlanSource ?? throw new BiliException("当前来源未提供音轨。")).AudioPlanAsync(item.Item, audio, ct)
                                    : await media.GetPlanAsync(item.Item, item.Quality, ct);
                                actualQuality = plan.Variant.Quality;
                                var parts = plan.Video.Concat(plan.Audio is { } a ? [a] : []).ToArray();
                                var files = new List<string>(); videoFiles = files; audioFile = null;
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
                                            Message = $"正在下载  {plan.Variant.Label}" }, false).GetAwaiter().GetResult();
                                    }, ct);
                                    if (p < plan.Video.Count) files.Add(file); else audioFile = file;
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
                        if (item.Item.X == null || item.AudioOutput != null)
                            await UpdateAsync(id, revision, t => t with { State = DownloadState.Merging, Progress = 96, Message = item.AudioOutput == null ? "合并音视频" : "处理音频" });
                        if (item.AudioOutput is { } export) await AudioExporter.ExportAsync(executable, videoFiles, export, item.Item, temporary, ct);
                        else if (item.Item.X == null) await MediaDownloader.MergeAsync(executable, videoFiles, audioFile, temporary, ct);
                        ct.ThrowIfCancellationRequested();
                        var filename = item.OutputFileName ?? (item.Item.X is {} post ? FileNaming.Render(item.Item,extension: item.AudioOutput?.Extension ?? post.OutputExtension) : (LinkParser.SafeName($"{item.Item.Title} P{item.Item.Index} {item.Item.PartTitle}") +
                            $" [{item.Item.Id.Replace(':', '-')}-{actualQuality}].mp4"));
                        var staging = Path.Combine(item.Directory, $".bilidownload-{id:N}.tmp");
                        try
                        {
                            await using (var source = File.OpenRead(temporary))
                            await using (var target = new FileStream(staging, FileMode.Create, FileAccess.Write, FileShare.None, 65536, true))
                                await source.CopyToAsync(target, ct);
                            await UpdateAsync(id, revision, t => {
                                ct.ThrowIfCancellationRequested();
                                var destination = FileNaming.MoveUnique(staging, item.Directory, filename);
                                return t with { State = DownloadState.Completed, Progress = 100, Message = "下载完成", FilePath = destination, FinalFileName = Path.GetFileName(destination) };
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
    public Task RemoveAsync(Guid id) => RemoveAsync(id, false);
    public async Task RemoveAsync(Guid id, bool deleteFile) {
        SemaphoreSlim execution;
        DownloadTaskData? removed;
        lock (sync) {
            if (!tasks.Remove(id, out removed)) return;
            waitingForAttachments.Remove(id); WakeDocuments(id);
            ++revisions[id];
            if (running.Remove(id, out var token)) token.Cancel();
            execution = executionGates[id];
        }
        Changed?.Invoke();
        await execution.WaitAsync();
        try {
            await store.DeleteAsync(id);
            var directory = Path.Combine(cache, id.ToString("N"));
            if (Directory.Exists(directory)) try { Directory.Delete(directory, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            if (deleteFile && removed?.State == DownloadState.Completed && !string.IsNullOrWhiteSpace(removed.FilePath) && File.Exists(removed.FilePath))
            {
                try { File.Delete(removed.FilePath); }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    throw new BiliException("任务记录已移除，但本地文件正在使用或无权删除。");
                }
            }
        } finally { execution.Release(); }
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
