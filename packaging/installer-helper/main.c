#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <aclapi.h>
#include <wchar.h>
#include <stdlib.h>

/* This process only prepares a dedicated directory. The unelevated Inno process
   owns the wizard, HKCU registration, shortcuts, file installation and launch. */
static BOOL enable_privilege(const wchar_t *name) {
    HANDLE token; TOKEN_PRIVILEGES privileges = {0};
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, &token)) return FALSE;
    privileges.PrivilegeCount = 1;
    LookupPrivilegeValueW(NULL, name, &privileges.Privileges[0].Luid);
    privileges.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED;
    SetLastError(ERROR_SUCCESS);
    BOOL ok = AdjustTokenPrivileges(token, FALSE, &privileges, 0, NULL, NULL) && GetLastError() == ERROR_SUCCESS;
    CloseHandle(token); return ok;
}
static BOOL equal_path(const wchar_t *a, const wchar_t *b) { return _wcsicmp(a, b) == 0; }
static DWORD validate(wchar_t *path) {
    size_t length = wcslen(path);
    if (length < 4 || path[1] != L':' || path[2] != L'\\' || wcschr(path + 2, L':')) return ERROR_INVALID_NAME;
    while (length > 3 && path[length - 1] == L'\\') path[--length] = 0;
    if (length <= 3 || path[length - 1] == L'.' || path[length - 1] == L' ') return ERROR_INVALID_NAME;
    wchar_t windows[MAX_PATH]; GetWindowsDirectoryW(windows, MAX_PATH);
    if (_wcsnicmp(path, windows, wcslen(windows)) == 0 && (path[wcslen(windows)] == 0 || path[wcslen(windows)] == L'\\')) return ERROR_ACCESS_DENIED;
    const KNOWNFOLDERID *folders[] = { &FOLDERID_ProgramFiles, &FOLDERID_ProgramFilesX64, &FOLDERID_ProgramFilesX86,
        &FOLDERID_UserProfiles, &FOLDERID_Profile, &FOLDERID_Public, &FOLDERID_ProgramData };
    for (unsigned i = 0; i < sizeof(folders) / sizeof(folders[0]); ++i) {
        PWSTR folder = NULL;
        if (SUCCEEDED(SHGetKnownFolderPath(folders[i], 0, NULL, &folder))) {
            BOOL forbidden = equal_path(path, folder); CoTaskMemFree(folder);
            if (forbidden) return ERROR_ACCESS_DENIED;
        }
    }
    /* Check every existing ancestor; never grant access through a junction. */
    for (size_t i = 3; i <= length; ++i) if (path[i] == L'\\' || path[i] == 0) {
        wchar_t saved = path[i]; path[i] = 0;
        DWORD attributes = GetFileAttributesW(path); path[i] = saved;
        if (attributes != INVALID_FILE_ATTRIBUTES && ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) || !(attributes & FILE_ATTRIBUTE_DIRECTORY))) return ERROR_ACCESS_DENIED;
    }
    return ERROR_SUCCESS;
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command, int show) {
    int count; wchar_t **args = CommandLineToArgvW(GetCommandLineW(), &count);
    if (!args || count != 4 || wcscmp(args[1], L"--prepare")) return ERROR_INVALID_PARAMETER;
    wchar_t path[32768];
    DWORD length = GetFullPathNameW(args[2], 32768, path, NULL);
    if (!length || length >= 32768) return ERROR_INVALID_NAME;
    DWORD error = validate(path); if (error) return (int)error;
    enable_privilege(SE_DEBUG_NAME); /* Also supports entering a different administrator's credentials. */
    HANDLE original = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, wcstoul(args[3], NULL, 10));
    HANDLE token = NULL;
    if (!original || !OpenProcessToken(original, TOKEN_QUERY, &token)) return ERROR_ACCESS_DENIED;
    DWORD size = 0; GetTokenInformation(token, TokenUser, NULL, 0, &size);
    TOKEN_USER *user = (TOKEN_USER *)LocalAlloc(LPTR, size);
    if (!user || !GetTokenInformation(token, TokenUser, user, size, &size)) return ERROR_ACCESS_DENIED;
    CloseHandle(token); CloseHandle(original);
    BOOL created = GetFileAttributesW(path) == INVALID_FILE_ATTRIBUTES;
    if (created) {
        int result = SHCreateDirectoryExW(NULL, path, NULL);
        if (result != ERROR_SUCCESS) return result;
    }
    HANDLE directory = CreateFileW(path, READ_CONTROL | WRITE_DAC | (created ? WRITE_OWNER : 0),
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    if (directory == INVALID_HANDLE_VALUE) return (int)GetLastError();
    wchar_t finalPath[32768]; BY_HANDLE_FILE_INFORMATION information;
    DWORD finalLength = GetFinalPathNameByHandleW(directory, finalPath, 32768, FILE_NAME_NORMALIZED);
    if (!finalLength || finalLength >= 32768 || !equal_path(finalPath + 4, path) ||
        !GetFileInformationByHandle(directory, &information) || (information.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) return ERROR_ACCESS_DENIED;
    PSID owner = NULL; PACL oldAcl = NULL, newAcl = NULL; PSECURITY_DESCRIPTOR descriptor = NULL;
    error = GetSecurityInfo(directory, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
        &owner, NULL, &oldAcl, NULL, &descriptor);
    if (error) return (int)error;
    if (!created && !EqualSid(owner, user->User.Sid)) return ERROR_ACCESS_DENIED;
    /* Existing nonempty directories need no privileged ACL propagation. A caller
       unable to repair its own ACL can choose a fresh directory instead. */
    if (!created) {
        wchar_t pattern[32768]; WIN32_FIND_DATAW entry;
        if (wcslen(path) + 3 >= 32768) return ERROR_INVALID_NAME;
        wcscpy(pattern, path); wcscat(pattern, L"\\*");
        HANDLE find = FindFirstFileW(pattern, &entry);
        if (find != INVALID_HANDLE_VALUE) {
            do { if (wcscmp(entry.cFileName, L".") && wcscmp(entry.cFileName, L"..")) { FindClose(find); return ERROR_DIR_NOT_EMPTY; } } while (FindNextFileW(find, &entry));
            FindClose(find);
        }
    }
    EXPLICIT_ACCESSW access = {0};
    access.grfAccessPermissions = FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE;
    access.grfAccessMode = GRANT_ACCESS; access.grfInheritance = SUB_CONTAINERS_AND_OBJECTS_INHERIT;
    access.Trustee.TrusteeForm = TRUSTEE_IS_SID; access.Trustee.TrusteeType = TRUSTEE_IS_USER; access.Trustee.ptstrName = (LPWSTR)user->User.Sid;
    error = SetEntriesInAclW(1, &access, oldAcl, &newAcl);
    if (!error) {
        BOOL changeOwner = created && !EqualSid(owner, user->User.Sid);
        if (changeOwner && !enable_privilege(SE_RESTORE_NAME)) error = ERROR_PRIVILEGE_NOT_HELD;
        if (!error) error = SetSecurityInfo(directory, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | (changeOwner ? OWNER_SECURITY_INFORMATION : 0),
            changeOwner ? user->User.Sid : NULL, NULL, newAcl, NULL);
    }
    if (newAcl) LocalFree(newAcl); LocalFree(descriptor); LocalFree(user); LocalFree(args); CloseHandle(directory);
    return (int)error;
}
