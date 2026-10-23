#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <wchar.h>

/* Keep the payload intact in app/. WinMain receives the original argument tail,
   so quoted Unicode arguments can be forwarded without parsing/re-quoting them. */
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR arguments, int show)
{
    WCHAR directory[32768], target[32768], working[32768], command[32768];
    DWORD length = GetModuleFileNameW(NULL, directory, 32768);
    if (!length || length >= 32768) return 1;
    WCHAR *separator = wcsrchr(directory, L'\\');
    if (!separator) return 1;
    *separator = 0;
    if (swprintf(target, 32768, L"%ls\\app\\BiliDownload.exe", directory) < 0 ||
        swprintf(working, 32768, L"%ls\\app", directory) < 0 ||
        swprintf(command, 32768, L"\"%ls\" %ls", target, arguments) < 0) return 1;
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&startup, sizeof(startup)); startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESHOWWINDOW; startup.wShowWindow = (WORD)show;
    ZeroMemory(&process, sizeof(process));
    if (!CreateProcessW(target, command, NULL, NULL, FALSE, 0, NULL, working, &startup, &process))
    {
        MessageBoxW(NULL, L"无法启动 BiliDownload。请确认 app 文件夹完整，或重新安装应用。", L"BiliDownload", MB_OK | MB_ICONERROR);
        return 1;
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    return 0;
}
