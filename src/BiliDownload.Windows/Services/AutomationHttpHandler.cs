namespace BiliDownload_Windows.Services;

// Only a separately copied app host named BiliDownload.QA.exe enables this offline UI test transport.
internal sealed class AutomationHttpHandler : HttpMessageHandler
{
    private readonly HttpClient http = new(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
    private readonly Uri endpoint;
    public AutomationHttpHandler()
    {
        endpoint = new(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "qa-endpoint.txt")).Trim());
        if (endpoint.Scheme != "http" || endpoint.Host != "127.0.0.1") throw new InvalidOperationException("QA endpoint must be loopback HTTP.");
    }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
        http.GetAsync(new Uri(endpoint, request.RequestUri!.PathAndQuery), cancellationToken);
    protected override void Dispose(bool disposing) { if (disposing) http.Dispose(); base.Dispose(disposing); }
}
