using BiliDownload.Core;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using Xunit;

namespace BiliDownload.Tests;

public sealed class ComponentInstallerTests
{
    [Fact] public async Task VerifiedComponentCanBeReusedAndRemovedWithoutTouchingOtherFiles()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-components-" + Guid.NewGuid());
        var bytes = Archive(); var package = Package(bytes); var handler = new Handler(bytes);
        using var http = new HttpClient(handler); var installer = new ComponentInstaller(root, http);
        try {
            Directory.CreateDirectory(root); var external = Path.Combine(root, "my-video.mp4"); File.WriteAllText(external, "keep");
            var path = await installer.InstallAsync(package);
            Assert.Equal("fixture", File.ReadAllText(path)); Assert.Equal(path, installer.InstalledPath(package));
            Assert.Equal(path, await installer.InstallAsync(package)); Assert.Equal(1, handler.Requests);
            installer.Remove(package); Assert.Null(installer.InstalledPath(package)); Assert.True(File.Exists(external));
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Fact] public async Task BadChecksumDoesNotReplaceAnInstalledComponent()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-components-" + Guid.NewGuid());
        var bytes = Archive(); var handler = new Handler(bytes); using var http = new HttpClient(handler);
        var installer = new ComponentInstaller(root, http);
        try {
            var good = Package(bytes); var path = await installer.InstallAsync(good);
            await Assert.ThrowsAsync<BiliException>(() => installer.InstallAsync(good with { Sha256 = new('a', 64) }));
            Assert.Equal("fixture", File.ReadAllText(path));
            Assert.Empty(Directory.GetDirectories(root, ".install-*"));
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Fact] public async Task VerifiedArchiveStillCannotEscapeItsInstallDirectory()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-components-" + Guid.NewGuid());
        var bytes = Archive("../escaped.exe"); using var http = new HttpClient(new Handler(bytes));
        try {
            var installer = new ComponentInstaller(root, http);
            await Assert.ThrowsAsync<BiliException>(() => installer.InstallAsync(Package(bytes)));
            Assert.Empty(Directory.GetFiles(root, "*.exe", SearchOption.AllDirectories));
            Assert.Empty(Directory.GetDirectories(root, ".install-*"));
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Fact] public async Task CancellationLeavesNoPartiallyInstalledTool()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-components-" + Guid.NewGuid());
        var bytes = Archive(); using var http = new HttpClient(new Handler(bytes));
        try {
            var installer = new ComponentInstaller(root, http); var package = Package(bytes);
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => installer.InstallAsync(package, ct: new CancellationToken(true)));
            Assert.Null(installer.InstalledPath(package)); Assert.Empty(Directory.GetDirectories(root));
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Fact] public void ManagedToolDoesNotOverrideAValidUserConfiguredPath()
    {
        var root = Path.Combine(Path.GetTempPath(), "bili-tools-" + Guid.NewGuid()); Directory.CreateDirectory(root);
        try {
            var managed = Path.Combine(root, "managed.exe"); var external = Path.Combine(root, "custom.exe");
            File.WriteAllText(managed, "fixture"); File.WriteAllText(external, "fixture");
            Assert.Equal(managed, ToolPathResolver.Resolve("ffmpeg", root, managed));
            Assert.Equal(external, ToolPathResolver.Resolve(external, root, managed));
        } finally { Directory.Delete(root, true); }
    }
    private static byte[] Archive(string? extraPath = null)
    {
        using var stream = new MemoryStream();
        using (var zip = new ZipArchive(stream, ZipArchiveMode.Create, true))
            foreach (var name in new[] { "ffmpeg.exe", "LICENSE.txt", extraPath }.OfType<string>())
                using (var entry = zip.CreateEntry(name).Open()) entry.Write(Encoding.UTF8.GetBytes("fixture"));
        return stream.ToArray();
    }
    private static ComponentPackage Package(byte[] bytes) => new("ffmpeg-minimal", "Minimal", "test", "https://example.com/component.zip", Convert.ToHexString(SHA256.HashData(bytes)), bytes.Length, 14);
    private sealed class Handler(byte[] bytes) : HttpMessageHandler
    {
        public int Requests { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        { Requests++; return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(bytes) }); }
    }
}
