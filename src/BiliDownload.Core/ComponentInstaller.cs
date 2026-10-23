using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BiliDownload.Core;

public record ComponentPackage(string Id, string Name, string Version, string Url, string Sha256, long DownloadBytes, long InstalledBytes);
public record ComponentProgress(string Message, double? Fraction);

/// <summary>Downloads owned optional components separately from account data and application files.</summary>
public sealed class ComponentInstaller(string directory, HttpClient http)
{
    public string? InstalledPath(ComponentPackage package)
    {
        var path = Destination(package);
        return File.Exists(Path.Combine(path, "installed.json")) && File.Exists(Path.Combine(path, "ffmpeg.exe")) ? Path.Combine(path, "ffmpeg.exe") : null;
    }
    private string Destination(ComponentPackage package)
    {
        if (!Regex.IsMatch(package.Id, "^[a-z0-9-]+$") || !Regex.IsMatch(package.Sha256, "^[a-fA-F0-9]{64}$"))
            throw new BiliException("组件清单无效。");
        return Path.GetFullPath(Path.Combine(directory, package.Id, package.Sha256[..12].ToLowerInvariant()));
    }
    public async Task<string> InstallAsync(ComponentPackage package, IProgress<ComponentProgress>? progress = null, CancellationToken ct = default)
    {
        if (InstalledPath(package) is { } existing) return existing;
        if (!Uri.TryCreate(package.Url, UriKind.Absolute, out var url) || url.Scheme != "https") throw new BiliException("组件下载地址无效。");
        Directory.CreateDirectory(directory);
        var staging = Path.Combine(directory, ".install-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        var archivePath = Path.Combine(staging, "download.zip");
        var extracted = Path.Combine(staging, "extracted");
        try
        {
            using (var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                response.EnsureSuccessStatusCode();
                var total = response.Content.Headers.ContentLength;
                if (total > 256L * 1024 * 1024) throw new BiliException("组件下载体积超出限制。");
                await using var input = await response.Content.ReadAsStreamAsync(ct);
                await using var output = File.Create(archivePath);
                var buffer = new byte[65536]; long received = 0;
                while (true)
                {
                    var count = await input.ReadAsync(buffer, ct); if (count == 0) break;
                    received += count;
                    if (received > 256L * 1024 * 1024) throw new BiliException("组件下载体积超出限制。");
                    await output.WriteAsync(buffer.AsMemory(0, count), ct);
                    progress?.Report(new("正在下载 " + package.Name, total is > 0 ? (double)received / total.Value : null));
                }
            }
            progress?.Report(new("校验下载文件", null));
            await using (var stream = File.OpenRead(archivePath))
            {
                var actual = Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
                if (!actual.Equals(package.Sha256, StringComparison.OrdinalIgnoreCase)) throw new BiliException("组件校验失败，文件未安装。请重新下载。");
            }
            progress?.Report(new("正在安装组件", null));
            Directory.CreateDirectory(extracted);
            using (var zip = ZipFile.OpenRead(archivePath))
            {
                if (zip.Entries.Count > 1000 || zip.Entries.Sum(e => e.Length) > 512L * 1024 * 1024) throw new BiliException("组件解压体积超出限制。");
                foreach (var entry in zip.Entries)
                {
                    ct.ThrowIfCancellationRequested();
                    var relative = entry.FullName.Replace('\\', '/');
                    if (relative.Split('/').Any(p => p is ".." or ".") || relative.Contains(':') || relative.StartsWith('/')) throw new BiliException("组件压缩包包含无效路径。");
                    var target = Path.GetFullPath(Path.Combine(extracted, relative));
                    if (!target.StartsWith(Path.GetFullPath(extracted) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new BiliException("组件路径超出安装目录。");
                    if (relative.EndsWith('/')) { Directory.CreateDirectory(target); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    await using var input = entry.Open(); await using var output = new FileStream(target, FileMode.CreateNew);
                    await input.CopyToAsync(output, ct);
                }
            }
            if (!File.Exists(Path.Combine(extracted, "ffmpeg.exe")) || !File.Exists(Path.Combine(extracted, "LICENSE.txt"))) throw new BiliException("组件文件不完整。");
            await File.WriteAllTextAsync(Path.Combine(extracted, "installed.json"), JsonSerializer.Serialize(package), ct);
            ct.ThrowIfCancellationRequested();
            var destination = Destination(package);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            if (InstalledPath(package) is { } installedByAnotherInstance) return installedByAnotherInstance;
            if (Directory.Exists(destination)) throw new BiliException("组件目录已存在但不完整，请先移除该组件后重试。");
            Directory.Move(extracted, destination);
            progress?.Report(new("组件已安装", 1));
            return Path.Combine(destination, "ffmpeg.exe");
        }
        finally { if (Directory.Exists(staging)) Directory.Delete(staging, true); }
    }
    public void Remove(ComponentPackage package)
    {
        var destination = Destination(package);
        if (File.Exists(Path.Combine(destination, "installed.json"))) Directory.Delete(destination, true);
    }
}
