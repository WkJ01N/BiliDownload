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

Windows optionally installs LGPL-only FFmpeg under `app/tools/`. The minimal build is compiled from pinned FFmpeg source using `scripts/build-ffmpeg.sh`, without external codecs; the full build retains the existing BtbN provenance manifest. The original license, exact build configuration and matching source archive accompany installed tools under `docs/ffmpeg/`, and are also included in both downloadable component ZIPs. Components downloaded from settings are stored with their notices and source under the user's component directory. The packaging script rejects builds configured with `--enable-gpl` or `--enable-nonfree`; this check does not replace preserving the build's original notices. FFmpeg source and license: https://ffmpeg.org/legal.html and https://github.com/FFmpeg/FFmpeg. The Android host uses MediaExtractor and MediaMuxer. The optional audio processor has its own offline FFmpeg build.

API behavior references used to independently implement parsing and signing:
https://github.com/bilibili-plugins/bilibili-api-collect/tree/master/docs

These are community observations of Bilibili interfaces, not an official Bilibili SDK.

## Optional YouTube engine

Windows installs CPython 3.13.13 (PSF), the official yt-dlp 2026.10.26 zipapp (Unlicense) with yt-dlp-ejs 0.8.0, and QuickJS-NG 0.18.0 (MIT). The component includes the upstream licenses. SHA-256 pins and upstream download URLs are in [the engine manifest](packaging/youtube.json).

The Android APK packages the CPython 3.12.11 and QuickJS 2025-04-26 runtime files from the SHA-256-pinned youtubedl-android 0.18.1 artifact. BiliDownload executes the tools as standalone subprocesses using a JSON-lines interface. The upstream runtime notices accompany the APK. BiliDownload downloads the pinned yt-dlp zipapp as a separate component on first use. Build `youtube-component-sources.zip` with `python scripts/prepare-youtube.py sources` to obtain the pinned source archives, wrapper sources and original runtime build instructions. [Android runtime build inputs](https://github.com/yausername/youtubedl-android/tree/0.18.1) [yt-dlp](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26) [EJS](https://github.com/yt-dlp/ejs/tree/0.8.0)

Radix Colors (MIT), Copyright (c) 2021-2022 Modulz; Copyright (c) 2022-Present WorkOS. Official palette revision dbdb85470547c7d34b9001f48fddb08ded335979. The full license is included in branding/RADIX-LICENSE.txt and Android assets/licenses/RADIX-LICENSE.txt. Source: https://github.com/radix-ui/colors

## Audio download and metadata

The Android APK packages a separate FFmpeg executable built from commit 5c4f5744cd with LAME 3.100 using Android NDK 28.2.13676358. The executable is built without network support. FFmpeg and LAME licenses are packaged in the APK. The source archive includes both source distributions and the build script needed to rebuild the executable for four ABIs.

The Android host uses the Apache-2.0 Kyant0 TagLib wrapper 1.0.5, TagLib at 3c917caf (LGPL/MPL) and utf8cpp at df857efc. Windows uses TagLibSharp 2.3.0 (LGPL-2.1). Matching source distributions, licenses and checksums are included in `audio-component-sources.zip`. [Audio source pins](packaging/audio.json) [FFmpeg](https://ffmpeg.org/) [LAME](https://lame.sourceforge.io/) [Android wrapper](https://github.com/Kyant0/taglib/tree/1.0.5) [TagLibSharp](https://github.com/mono/taglib-sharp/tree/TaglibSharp-2.3.0.0)
