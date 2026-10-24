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

These are community observations of Bilibili interfaces, not an official Bilibili SDK. No yt-dlp implementation is bundled.

Radix Colors (MIT), Copyright (c) 2021-2022 Modulz; Copyright (c) 2022-Present WorkOS. Official palette revision dbdb85470547c7d34b9001f48fddb08ded335979. The full license is included in branding/RADIX-LICENSE.txt and Android assets/licenses/RADIX-LICENSE.txt. Source: https://github.com/radix-ui/colors
