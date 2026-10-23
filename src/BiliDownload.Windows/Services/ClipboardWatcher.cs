using System.Runtime.InteropServices;

namespace BiliDownload_Windows.Services;

public sealed class ClipboardWatcher : IDisposable
{
    private readonly nint window;
    private readonly SubclassProc callback;
    private readonly Action changed;
    public ClipboardWatcher(nint window, Action changed)
    {
        this.window = window; this.changed = changed; callback = WindowProc;
        if (!SetWindowSubclass(window, callback, 1, 0)) throw new InvalidOperationException("无法注册剪贴板监听。");
        if (!AddClipboardFormatListener(window)) {
            RemoveWindowSubclass(window, callback, 1); throw new InvalidOperationException("无法注册剪贴板监听。");
        }
    }
    private nint WindowProc(nint hwnd, uint message, nuint wparam, nint lparam, nuint id, nuint data)
    {
        if (message == 0x031D) changed();
        return DefSubclassProc(hwnd, message, wparam, lparam);
    }
    public void Dispose() { RemoveClipboardFormatListener(window); RemoveWindowSubclass(window, callback, 1); }
    private delegate nint SubclassProc(nint hwnd, uint message, nuint wparam, nint lparam, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
    [DllImport("comctl32.dll")] private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wparam, nint lparam);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool AddClipboardFormatListener(nint hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool RemoveClipboardFormatListener(nint hwnd);
}
