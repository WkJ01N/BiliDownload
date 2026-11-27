using BiliDownload.Core;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.Web.WebView2.Core;
using Windows.Graphics;

namespace BiliDownload_Windows.Services;

public enum WebLoginPlatform { YouTube, X }

public sealed class WebLoginWindow : Window
{
    private readonly WebLoginPlatform platform;
    private readonly string tempProfileDir;
    private readonly TaskCompletionSource<LoginResult> tcs = new();
    private readonly WebView2 webView = new();
    private readonly TextBlock statusText = new() { VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap };
    private readonly ProgressBar progressBar = new() { IsIndeterminate = true, Height = 2, Visibility = Visibility.Collapsed };
    private readonly Button completeButton = new() { Content = "完成登录", Style = (Style)Application.Current.Resources["SaltPillAccentButtonStyle"] };
    private readonly Button cancelButton = new() { Content = "取消", Style = (Style)Application.Current.Resources["SaltTonalButtonStyle"] };
    private readonly Grid root = new();
    private bool finished;
    private bool initialized;

    public static Task<LoginResult> ShowAsync(WebLoginPlatform platform)
    {
        var window = new WebLoginWindow(platform);
        window.Activate();
        return window.tcs.Task;
    }

    private WebLoginWindow(WebLoginPlatform platform)
    {
        this.platform = platform;
        tempProfileDir = Path.Combine(Path.GetTempPath(), "BiliDownload-WebLogin-" + Guid.NewGuid().ToString("N"));
        Title = platform == WebLoginPlatform.X ? "登录 X" : "登录 YouTube";
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new SizeInt32(960, 740));

        if (App.Main?.Content is FrameworkElement mainRoot)
            root.RequestedTheme = mainRoot.RequestedTheme;

        root.Background = (Brush)Application.Current.Resources["SaltCanvasBackgroundBrush"];
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var header = new Border
        {
            Padding = new Thickness(16, 12, 16, 12),
            Background = (Brush)Application.Current.Resources["SaltCardBackgroundBrush"],
            BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(0, 0, 0, 1),
        };
        var headerGrid = new Grid { ColumnSpacing = 12 };
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        statusText.Text = platform == WebLoginPlatform.X
            ? "请在下方网页登录 X，完成后点击右侧「完成登录」。"
            : "请在下方网页登录 Google / YouTube，完成后点击右侧「完成登录」。";
        headerGrid.Children.Add(statusText);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        cancelButton.Click += (_, _) => { Resolve(new(LoginOutcome.Canceled)); Close(); };
        completeButton.Click += async (_, _) => await CompleteLoginAsync();
        actions.Children.Add(cancelButton);
        actions.Children.Add(completeButton);
        Grid.SetColumn(actions, 1);
        headerGrid.Children.Add(actions);
        header.Child = headerGrid;

        Grid.SetRow(progressBar, 1);
        Grid.SetRow(webView, 2);
        root.Children.Add(header);
        root.Children.Add(progressBar);
        root.Children.Add(webView);
        Content = root;

        Closed += (_, _) =>
        {
            try { webView.Close(); } catch { }
            Resolve(new(LoginOutcome.Canceled));
            _ = CleanupProfileAsync(tempProfileDir);
        };

        _ = InitializeAsync();
    }

    private async Task InitializeAsync()
    {
        try
        {
            progressBar.Visibility = Visibility.Visible;
            Directory.CreateDirectory(tempProfileDir);
            var env = await CoreWebView2Environment.CreateWithOptionsAsync(string.Empty, tempProfileDir, null);
            await webView.EnsureCoreWebView2Async(env);
            if (finished || webView.CoreWebView2 == null) return;
            initialized = true;
            webView.NavigationStarting += (_, _) => progressBar.Visibility = Visibility.Visible;
            webView.NavigationCompleted += async (_, _) =>
            {
                progressBar.Visibility = Visibility.Collapsed;
                await RefreshDetectedHintAsync();
            };
            var url = platform == WebLoginPlatform.X
                ? "https://x.com/i/flow/login"
                : "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2F";
            webView.CoreWebView2.Navigate(url);
        }
        catch (Exception)
        {
            progressBar.Visibility = Visibility.Collapsed;
            Resolve(new(LoginOutcome.Failed, "无法启动内置浏览器组件（WebView2），可通过「更多 -> 导入 cookies.txt」作为备用方案。"));
            Close();
        }
    }

    private async Task RefreshDetectedHintAsync()
    {
        if (!initialized || webView.CoreWebView2 == null) return;
        try
        {
            var cookies = await ReadPlatformCookiesAsync();
            YoutubeCookies.FromBrowserCookies(cookies, platform == WebLoginPlatform.X);
            statusText.Text = "已检测到登录状态，请点击右侧「完成登录」保存。";
        }
        catch
        {
            // Not logged in yet; keep prompting calmly.
        }
    }

    private async Task<List<BrowserCookie>> ReadPlatformCookiesAsync()
    {
        var manager = webView.CoreWebView2.CookieManager;
        var targets = platform == WebLoginPlatform.X
            ? new[] { "https://x.com", "https://twitter.com" }
            : new[] { "https://www.youtube.com", "https://music.youtube.com" };
        var result = new List<BrowserCookie>();
        var seen = new HashSet<(string, string, string)>(EqualityComparer<(string, string, string)>.Default);
        foreach (var target in targets)
        {
            var list = await manager.GetCookiesAsync(target);
            foreach (var c in list)
            {
                if (!seen.Add((c.Domain, c.Path, c.Name))) continue;
                var expires = c.IsSession || double.IsNaN(c.Expires) || c.Expires <= 0 ? 0L : (long)Math.Floor(c.Expires);
                result.Add(new BrowserCookie(c.Domain, c.Path, c.IsSecure, c.IsHttpOnly, expires, c.Name, c.Value));
            }
        }
        return result;
    }

    private async Task CompleteLoginAsync()
    {
        if (!initialized || webView.CoreWebView2 == null) return;
        completeButton.IsEnabled = false;
        try
        {
            var cookies = await ReadPlatformCookiesAsync();
            var isX = platform == WebLoginPlatform.X;
            var netscape = YoutubeCookies.FromBrowserCookies(cookies, isX);
            App.Services.SaveSessionCookies(netscape, isX);
            Resolve(new(LoginOutcome.Success));
            Close();
        }
        catch (Exception ex)
        {
            statusText.Text = ex is BiliException ? ex.Message : "无法读取或保存登录信息，请重试。";
            completeButton.IsEnabled = true;
        }
    }

    private void Resolve(LoginResult result)
    {
        if (finished) return;
        finished = true;
        tcs.TrySetResult(result);
    }

    private static async Task CleanupProfileAsync(string dir)
    {
        for (var i = 0; i < 6; i++)
        {
            await Task.Delay(400);
            try
            {
                if (Directory.Exists(dir)) Directory.Delete(dir, true);
                return;
            }
            catch { }
        }
    }
}
