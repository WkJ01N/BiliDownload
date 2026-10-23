using System.IO.Pipes;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace BiliDownload_Windows.Services;

public sealed record ActivationRequest(string Action, Guid? Id = null);

public sealed class SingleInstance : IDisposable
{
    private readonly Mutex mutex;
    private readonly string name;
    private readonly CancellationTokenSource stop = new();
    public bool IsPrimary { get; }
    public SingleInstance(string directory)
    {
        var identity = WindowsIdentity.GetCurrent().User!.Value + "|" + Path.GetFullPath(directory).ToUpperInvariant();
        name = "BiliDownload-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..24];
        mutex = new(false, "Global\\" + name);
        try { IsPrimary = mutex.WaitOne(0); } catch (AbandonedMutexException) { IsPrimary = true; }
    }
    public void Listen(Action<ActivationRequest> receive) => _ = Task.Run(async () => {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(stop.Token);
                var bytes = new byte[512]; var count = 0;
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop.Token); timeout.CancelAfter(3000);
                while (count < bytes.Length)
                {
                    var read = await server.ReadAsync(bytes.AsMemory(count, 1), timeout.Token);
                    if (read == 0 || bytes[count] == '\n') break;
                    count += read;
                }
                if (count < bytes.Length && JsonSerializer.Deserialize<ActivationRequest>(bytes.AsSpan(0, count)) is { } request &&
                    (request.Action is "open" or "exit" || request.Action == "notification" && request.Id.HasValue)) receive(request);
                await server.WriteAsync(new byte[] { 1 }, timeout.Token);
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException or JsonException) {
                if (!stop.IsCancellationRequested) await Task.Delay(50);
            }
        }
    });
    public async Task ForwardAsync(ActivationRequest request)
    {
        await using var client = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await client.ConnectAsync(timeout.Token);
        await client.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(request) + "\n"), timeout.Token);
        await client.ReadExactlyAsync(new byte[1], timeout.Token);
    }
    public void Dispose() { stop.Cancel(); if (IsPrimary) mutex.ReleaseMutex(); mutex.Dispose(); }
}
