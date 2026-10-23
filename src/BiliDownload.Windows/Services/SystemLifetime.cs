using System.Runtime.InteropServices;

namespace BiliDownload_Windows.Services;

public sealed class SystemLifetime : IDisposable
{
    private readonly nint window;
    private readonly SubclassProc callback;
    public SystemLifetime(nint window, Action preparing, Action<bool> ending)
    {
        this.window = window;
        callback = (hwnd, message, wp, lp, id, data) => {
            if (message == 0x0011) { preparing(); return 1; } // WM_QUERYENDSESSION / Restart Manager
            if (message == 0x0016) ending(wp != 0);
            return DefSubclassProc(hwnd, message, wp, lp);
        };
        SetWindowSubclass(window, callback, 3, 0);
    }
    public void Dispose() => RemoveWindowSubclass(window, callback, 3);
    private delegate nint SubclassProc(nint hwnd, uint message, nuint wp, nint lp, nuint id, nuint data);
    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
    [DllImport("comctl32.dll")] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
    [DllImport("comctl32.dll")] private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wp, nint lp);
}
