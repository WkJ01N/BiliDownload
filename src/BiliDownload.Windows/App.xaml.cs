using BiliDownload_Windows.Services;
using Microsoft.UI.Xaml;

namespace BiliDownload_Windows;

public partial class App : Application
{
    public static AppServices Services { get; private set; } = null!;
    public static MainWindow Main { get; private set; } = null!;
    public App()
    {
        UnhandledException += (_, e) => WriteSmokeFailure(e.Exception);
        try { InitializeComponent(); } catch (Exception ex) { WriteSmokeFailure(ex); throw; }
    }
    private static void WriteSmokeFailure(Exception exception)
    {
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Length >= 3 && arguments[1] == "--smoke-test") File.WriteAllText(arguments[2], exception.ToString());
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs();
        var smoke = arguments.Length >= 3 && arguments[1] == "--smoke-test";
        try { Services = new(smoke ? Path.Combine(Path.GetDirectoryName(Path.GetFullPath(arguments[2]))!, "smoke-data") : null); Main = new(); Main.Activate(); }
        catch (Exception ex) { WriteSmokeFailure(ex); throw; }
        if (smoke)
        {
            var path = arguments[2];
            var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
            timer.Tick += async (_, _) => {
                timer.Stop();
                try {
                    if (arguments.Length >= 4 && int.TryParse(arguments[3], out var width)) Main.ConfigureSmoke(width, arguments.ElementAtOrDefault(4) ?? "home", arguments.ElementAtOrDefault(5) ?? "Completed");
                    await Task.Delay(500);
                    var detail = await Main.VerifyCommandsAsync(); await Main.CaptureAsync(Path.ChangeExtension(path, ".png")); File.WriteAllText(path, "WinUI window initialized successfully\n" + detail);
                }
                catch (Exception ex) { File.WriteAllText(path, "UI capture failed: " + ex.GetType().Name); }
                Main.ExitApplication();
            };
            timer.Start();
        }
    }
}
