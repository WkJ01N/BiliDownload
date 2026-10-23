# Third-party components

BiliDownload's own code is MIT licensed. Dependencies retain their respective licenses.

- The Windows shell and default assets originate from Microsoft's Windows App SDK WinUI C# NavigationView template: https://github.com/microsoft/WindowsAppSDK (MIT).
- Microsoft Windows App SDK / WinUI: https://github.com/microsoft/WindowsAppSDK and https://github.com/microsoft/microsoft-ui-xaml (MIT).
- .NET runtime and Microsoft.Data.Sqlite: https://github.com/dotnet/runtime and https://github.com/dotnet/efcore (MIT); SQLite: https://sqlite.org/copyright.html (public domain).
- QRCoder: https://github.com/codebude/QRCoder (MIT).
- AndroidX / Jetpack Compose: https://android.googlesource.com/platform/frameworks/support/ (Apache-2.0).
- Material Design navigation icon paths: https://github.com/google/material-design-icons (Apache-2.0).
- Kotlin and kotlinx.coroutines: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines (Apache-2.0).
- Gson: https://github.com/google/gson (Apache-2.0).
- Gradle Wrapper: https://github.com/gradle/gradle (Apache-2.0).

Windows packages an LGPL-only FFmpeg executable under `app/tools/`. The original license, exact build configuration, matching source archive/source links and third-party notices accompany it under `docs/ffmpeg/`. The packaging script rejects builds configured with `--enable-gpl` or `--enable-nonfree`; this check does not replace preserving the build's original notices. FFmpeg source and license: https://ffmpeg.org/legal.html and https://github.com/FFmpeg/FFmpeg. Android uses the system MediaExtractor / MediaMuxer and does not bundle FFmpeg.

API behavior references used to independently implement parsing and signing:
https://github.com/bilibili-plugins/bilibili-api-collect/tree/master/docs

These are community observations of Bilibili interfaces, not an official Bilibili SDK. No yt-dlp implementation is bundled.
