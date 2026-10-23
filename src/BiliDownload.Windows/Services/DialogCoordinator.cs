using Microsoft.UI.Xaml.Controls;

namespace BiliDownload_Windows.Services;

public static class DialogCoordinator
{
    private static readonly SemaphoreSlim Gate = new(1);
    public static async Task<ContentDialogResult> ShowAsync(ContentDialog dialog)
    {
        await Gate.WaitAsync();
        try { return App.Main.IsExiting ? ContentDialogResult.None : await dialog.ShowAsync(); }
        finally { Gate.Release(); }
    }
    public static async Task WaitForIdleAsync() { await Gate.WaitAsync(); Gate.Release(); }
}
