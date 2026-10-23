namespace BiliDownload.Core;

public static class ToolPathResolver
{
    public static string Resolve(string configured, string applicationDirectory, string? managedExecutable = null)
    {
        if (Path.IsPathFullyQualified(configured) && File.Exists(configured)) return configured;
        if (managedExecutable != null && File.Exists(managedExecutable) && (configured is "ffmpeg" or "ffmpeg.exe" || Path.IsPathFullyQualified(configured))) return managedExecutable;
        var bundled = Path.Combine(applicationDirectory, "tools", "ffmpeg.exe");
        // Only fall back for the default command or a missing absolute path. Keep custom PATH commands intact.
        if (File.Exists(bundled) && (configured is "ffmpeg" or "ffmpeg.exe" || Path.IsPathFullyQualified(configured) && !File.Exists(configured))) return bundled;
        return configured;
    }
}
