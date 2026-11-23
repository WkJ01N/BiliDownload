# Third-party components

BiliDownload's own code is MIT licensed. Dependencies retain their respective licenses.

- The Windows shell originates from Microsoft's Windows App SDK WinUI C# NavigationView template: https://github.com/microsoft/WindowsAppSDK (MIT). The pink B/download application icon is an original project asset, based on the owner's reference, with editable SVG and platform export script in this repository (MIT).
- Microsoft Windows App SDK / WinUI: https://github.com/microsoft/WindowsAppSDK and https://github.com/microsoft/microsoft-ui-xaml (MIT).
- .NET runtime and Microsoft.Data.Sqlite: https://github.com/dotnet/runtime and https://github.com/dotnet/efcore (MIT); SQLite: https://sqlite.org/copyright.html (public domain).
- QRCoder: https://github.com/codebude/QRCoder (MIT).
- AndroidX / Jetpack Compose: https://android.googlesource.com/platform/frameworks/support/ (Apache-2.0).
- Material Design navigation icon paths: https://github.com/google/material-design-icons (Apache-2.0).
- Kotlin and kotlinx.coroutines: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines (Apache-2.0).
- Gson: https://github.com/google/gson (Apache-2.0).
- Gradle Wrapper: https://github.com/gradle/gradle (Apache-2.0).

Windows optionally installs LGPL-only FFmpeg under `app/tools/`. The minimal build is compiled from pinned FFmpeg source using `scripts/build-ffmpeg.sh`, without external codecs; the full build retains the existing BtbN provenance manifest. The original license, exact build configuration and matching source archive accompany installed tools under `docs/ffmpeg/`, and are also included in both downloadable component ZIPs. Components downloaded from settings are stored with their notices and source under the user's component directory. The packaging script rejects builds configured with `--enable-gpl` or `--enable-nonfree`; this check does not replace preserving the build's original notices. FFmpeg source and license: https://ffmpeg.org/legal.html and https://github.com/FFmpeg/FFmpeg. Android uses the system MediaExtractor / MediaMuxer and does not bundle FFmpeg.

API behavior references used to independently implement parsing and signing:
https://github.com/bilibili-plugins/bilibili-api-collect/tree/master/docs

These are community observations of Bilibili interfaces, not an official Bilibili SDK.

## Optional YouTube engine

Windows installs CPython 3.13.13 (PSF), the official yt-dlp 2026.10.26 zipapp (Unlicense) with yt-dlp-ejs 0.8.0, and QuickJS-NG 0.18.0 (MIT). The component includes the upstream licenses. SHA-256 pins and upstream download URLs are in [the engine manifest](packaging/youtube.json).

The separately installed Android YouTube extension uses youtubedl-android 0.18.1, CPython 3.12.11 and QuickJS 2025-04-26. The extension APK is distributed under GPLv3 with its upstream notices. The host app communicates with it using the signature-protected AIDL interface. The release includes `youtube-component-sources.zip` with engine source archives, Android wrapper sources, native runtime build instructions and extension integration code. The release tag contains the complete app and Gradle build configuration. [Android wrapper and build inputs](https://github.com/yausername/youtubedl-android/tree/0.18.1) [yt-dlp](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26) [EJS](https://github.com/yt-dlp/ejs/tree/0.8.0)

Radix Colors (MIT), Copyright (c) 2021-2022 Modulz; Copyright (c) 2022-Present WorkOS. Official palette revision dbdb85470547c7d34b9001f48fddb08ded335979. The full license is included in branding/RADIX-LICENSE.txt and Android assets/licenses/RADIX-LICENSE.txt. Source: https://github.com/radix-ui/colors
