using System.Runtime.InteropServices;

namespace BiliDownload_Windows.Services;

public sealed class TrayIcon : IDisposable
{
    private const uint Callback = 0x8011;
    private Data data;
    private readonly SubclassProc callback;
    private readonly Action open;
    private readonly Action exit;
    private readonly uint taskbarCreated = RegisterWindowMessage("TaskbarCreated");
    public bool IsAvailable { get; private set; }
    public TrayIcon(nint window, Action open, Action exit)
    {
        this.open = open; this.exit = exit; callback = WindowProc;
        data = new Data { Size = (uint)Marshal.SizeOf<Data>(), Window = window, Id = 1, Flags = 7, Message = Callback,
            Icon = LoadImage(0, Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"), 1, 16, 16, 0x10),
            Tip = "BiliDownload · 双击打开，右键退出", Info = "", InfoTitle = "" };
        SetWindowSubclass(window, callback, 2, 0); IsAvailable = ShellNotifyIcon(0, ref data);
    }
    private nint WindowProc(nint hwnd, uint message, nuint wp, nint lp, nuint id, nuint reference)
    {
        if (message == taskbarCreated) IsAvailable = ShellNotifyIcon(0, ref data);
        if (message == Callback)
        {
            if ((long)lp == 0x203) open();
            else if ((long)lp == 0x205)
            {
                var menu = CreatePopupMenu();
                try
                {
                    AppendMenu(menu, 0, 1, "打开 BiliDownload"); AppendMenu(menu, 0, 2, "退出");
                    GetCursorPos(out var point); SetForegroundWindow(hwnd);
                    var command = TrackPopupMenu(menu, 0x102, point.X, point.Y, 0, hwnd, 0);
                    if (command == 1) open(); else if (command == 2) exit();
                }
                finally { DestroyMenu(menu); }
            }
        }
        return DefSubclassProc(hwnd, message, wp, lp);
    }
    public void Dispose() { ShellNotifyIcon(2, ref data); RemoveWindowSubclass(data.Window, callback, 2); if (data.Icon != 0) DestroyIcon(data.Icon); }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct Data
    {
        public uint Size; public nint Window; public uint Id, Flags, Message; public nint Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Tip;
        public uint State, StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Info;
        public uint Timeout;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string InfoTitle;
        public uint InfoFlags; public Guid Guid; public nint BalloonIcon;
    }
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
    private delegate nint SubclassProc(nint hwnd, uint message, nuint wparam, nint lparam, nuint id, nuint data);
    [DllImport("shell32.dll", EntryPoint = "Shell_NotifyIconW", CharSet = CharSet.Unicode)] private static extern bool ShellNotifyIcon(uint message, ref Data data);
    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
    [DllImport("comctl32.dll")] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
    [DllImport("comctl32.dll")] private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wparam, nint lparam);
    [DllImport("user32.dll", EntryPoint = "LoadImageW", CharSet = CharSet.Unicode)] private static extern nint LoadImage(nint instance, string name, uint type, int cx, int cy, uint flags);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(nint icon);
    [DllImport("user32.dll")] private static extern nint CreatePopupMenu();
    [DllImport("user32.dll", EntryPoint = "AppendMenuW", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(nint menu, uint flags, nuint id, string text);
    [DllImport("user32.dll")] private static extern int TrackPopupMenu(nint menu, uint flags, int x, int y, int reserved, nint hwnd, nint rectangle);
    [DllImport("user32.dll")] private static extern bool DestroyMenu(nint menu);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(nint window);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll", EntryPoint = "RegisterWindowMessageW", CharSet = CharSet.Unicode)] private static extern uint RegisterWindowMessage(string message);
}
