#!/usr/bin/env bash
set -euo pipefail
source_archive="$1"
build_directory="$2"
output_directory="$3"
export revision="$4"
if command -v cygpath >/dev/null; then
  source_archive="$(cygpath -u "$source_archive")"
  build_directory="$(cygpath -u "$build_directory")"
  output_directory="$(cygpath -u "$output_directory")"
fi
mkdir -p "$build_directory/source" "$build_directory/build" "$output_directory"
tar -xzf "$source_archive" -C "$build_directory/source" --strip-components=1
# In-source builds also work with native Windows GNU Make (which cannot read /e/... includes).
cd "$build_directory/source"
printf '%s\n' "$4" > VERSION
options=(
  --target-os=mingw32 --arch=x86_64 --disable-autodetect
  --disable-everything --disable-network --disable-doc --disable-debug
  --disable-ffplay --disable-ffprobe --enable-ffmpeg --enable-small
  --disable-x86asm --disable-shared --enable-static
  --disable-avdevice --disable-swscale --disable-swresample
  --enable-protocol=file --enable-demuxer=mov,flv,mpegts,concat
  --enable-muxer=mp4 --enable-parser=h264,aac
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,extract_extradata
  --extra-version=bilidownload-minimal
  --extra-cflags=-Os --extra-ldflags=-static
)
./configure "${options[@]}"
make_command="${MAKE:-}"
if [[ -z "$make_command" ]]; then
  if command -v make >/dev/null; then make_command=make; else make_command=mingw32-make; fi
fi
if [[ "$make_command" == *mingw32-make* ]]; then
  "$make_command" -j4 "SHELL=$(cygpath -ms "$(command -v sh)")" ffmpeg.exe
else
  "$make_command" -j4 ffmpeg.exe
fi
strip ffmpeg.exe
cp ffmpeg.exe "$output_directory/ffmpeg.exe"
cp "$source_archive" "$output_directory/FFmpeg-source.tar.gz"
cp "$build_directory/source/COPYING.LGPLv2.1" "$output_directory/LICENSE.txt"
./ffmpeg.exe -version > "$output_directory/BUILD-CONFIGURATION.txt"
printf '%s\n' 'FFmpeg source is unchanged. Built with scripts/build-ffmpeg.sh from the pinned, SHA-256 verified source archive.' > "$output_directory/SOURCE-INFORMATION.txt"
