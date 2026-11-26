#!/usr/bin/env bash
# Build the offline audio processor for all supported Android ABIs.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cache="$root/.build/audio"
out="$root/android/audio/build/generated/jniLibs"
mkdir -p "$cache" "$out"
fetch() {
  local path="$1" url="$2" digest="$3"
  if [[ ! -f "$path" ]] || ! echo "$digest  $path" | sha256sum -c --status; then
    curl --retry 3 -fLsS "$url" -o "$path.part"
    echo "$digest  $path.part" | sha256sum -c --status
    mv "$path.part" "$path"
  fi
}
fetch "$cache/lame-3.100.tar.gz" https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
# GitHub can regenerate upstream tarball headers. The project's immutable component
# contains the exact original archive used to build the Windows FFmpeg component.
ffmpeg_sha=a34b707c67140a37ddd11c5475fa09135301a7c667f1d78c5702d544bba6ce77
if [[ ! -f "$cache/ffmpeg.tar.gz" ]] || ! echo "$ffmpeg_sha  $cache/ffmpeg.tar.gz" | sha256sum -c --status; then
  fetch "$cache/ffmpeg-minimal.zip" https://github.com/WkJ01N/BiliDownload/releases/download/v0.1.2/ffmpeg-minimal-win-x64.zip d6d207338b2f33beb39b671796109592ad324ae8860373f23fcadf1628168fb4
  python -c 'import shutil,sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); src=z.open("FFmpeg-source.tar.gz"); dst=open(sys.argv[2],"wb"); shutil.copyfileobj(src,dst); dst.close()' "$cache/ffmpeg-minimal.zip" "$cache/ffmpeg.tar.gz.part"
  echo "$ffmpeg_sha  $cache/ffmpeg.tar.gz.part" | sha256sum -c --status
  mv "$cache/ffmpeg.tar.gz.part" "$cache/ffmpeg.tar.gz"
fi
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/28.2.13676358}"
host=linux-x86_64
if command -v cygpath >/dev/null; then ndk="$(cygpath -u "$ndk")"; host=windows-x86_64; fi
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
export PATH="$bin:$PATH"
make_cmd="${MAKE:-make}"
for abi in "${@:-arm64-v8a armeabi-v7a x86_64 x86}"; do for arch in $abi; do
  arch_options=()
  case "$arch" in
    arm64-v8a) target=aarch64-linux-android; cpu=aarch64;;
    armeabi-v7a) target=armv7a-linux-androideabi; cpu=arm;;
    x86_64) target=x86_64-linux-android; cpu=x86_64;;
    x86) target=i686-linux-android; cpu=x86; arch_options+=(--disable-inline-asm);;
    *) exit 2;;
  esac
  work="$cache/$arch"
  mkdir -p "$work"/{lame,ffmpeg,prefix} "$out/$arch"
  stamp="$out/$arch/libaudio-ffmpeg.so"
  if [[ -s "$stamp" && "$stamp" -nt "$0" ]]; then continue; fi
  tar -xzf "$cache/lame-3.100.tar.gz" -C "$work/lame" --strip-components=1
  tar -xzf "$cache/ffmpeg.tar.gz" -C "$work/ffmpeg" --strip-components=1
  prefix="$work/prefix"
  if command -v cygpath >/dev/null; then prefix="$(cygpath -ms "$prefix")"; fi
  export CC="clang --target=${target}29" AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip
  (cd "$work/lame"
   ac_cv_func_strtol=yes CFLAGS="-O2 -fPIC" ./configure --host="$target" --prefix="$prefix" --disable-shared --enable-static --disable-frontend --disable-decoder --disable-nasm
   "$make_cmd" -j4 -C libmp3lame
   "$make_cmd" -C libmp3lame install
   "$make_cmd" -C include install)
  (cd "$work/ffmpeg"
   ./configure --target-os=android --arch="$cpu" "${arch_options[@]}" --enable-cross-compile --cc="$CC" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
     --disable-autodetect --disable-everything --disable-network --disable-doc --disable-debug --disable-x86asm --disable-ffplay --disable-ffprobe --disable-avdevice \
     --enable-ffmpeg --enable-small --enable-static --disable-shared --enable-protocol=file \
     --enable-demuxer=mov,matroska,ogg,flac,aac,mp3,flv,mpegts,concat,eac3,ac3 \
     --enable-muxer=mp4,mp3,opus,flac,gif --enable-parser=aac,ac3,flac,h264,mpegaudio,opus \
     --enable-decoder=aac,aac_fixed,opus,flac,eac3,ac3,h264,mp3,vorbis \
     --enable-encoder=libmp3lame,gif --enable-libmp3lame \
     --enable-filter=aresample,aformat,anull,format,fps,null,palettegen,paletteuse,scale,split --enable-bsf=aac_adtstoasc \
     --extra-cflags="-Os -fPIC -I$prefix/include" --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384 -pie"
   "$make_cmd" -j"${BUILD_JOBS:-6}" ffmpeg
   llvm-strip -o "$stamp.tmp" ffmpeg
   mv "$stamp.tmp" "$stamp")
done; done
