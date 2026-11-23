"""Check encoded audio exported through the Android app's MediaStore on an emulator."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

fixtures = Path(sys.argv[1])
package = "io.github.bilidownload"
cases = [
    ("aac.m4a", 0, "m4a"), ("opus.webm", 0, "opus"), ("lossless.m4a", 0, "flac"),
    ("dolby.m4a", 0, "m4a"), ("mixed.mp4", 0, "m4a"), ("dolby.m4a", 320, "mp3"),
    ("opus.webm", 192, "mp3"), ("aac.m4a", 128, "mp3"),
]


def streams(path):
    raw = subprocess.check_output(["ffprobe", "-v", "error", "-show_streams", "-of", "json", path])
    return json.loads(raw)["streams"]


def packet_hash(path):
    return subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-map", "0:a:0",
                                    "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-"]).strip()


def verify(source, rate, dest):
    rel = "cache/qa-audio-exports/" + dest.name
    with dest.open("wb") as output:
        subprocess.run(["adb", "-e", "exec-out", "run-as", package, "cat", rel], stdout=output, check=True)
    original = next(s for s in streams(source) if s["codec_type"] == "audio")
    exported = streams(dest)
    if len(exported) != 1:
        raise ValueError(f"expected one audio stream, got {len(exported)} streams")
    result = exported[0]
    expected_codec = "mp3" if rate else original["codec_name"]
    channels = min(2, original["channels"]) if rate else original["channels"]
    if result["codec_name"] != expected_codec or result["channels"] != channels:
        raise ValueError(f"expected {expected_codec}/{channels} channels, got {result['codec_name']}/{result['channels']}")
    if rate and int(result["bit_rate"]) != rate * 1000:
        raise ValueError(f"expected {rate} kb/s, got {result['bit_rate']} b/s")
    if not rate and packet_hash(source) != packet_hash(dest):
        raise ValueError("original encoded audio packet data changed")
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", dest,
                    "-map", "0:a:0", "-f", "null", "-"], check=True)


failures = []
with tempfile.TemporaryDirectory() as work:
    for sample, rate, ext in cases:
        name = f"{sample}-{rate}.{ext}"
        try:
            verify(fixtures / sample, rate, Path(work) / name)
            print(f"PASS {name}")
        except (ValueError, KeyError, subprocess.CalledProcessError, StopIteration) as error:
            failures.append(f"{name}: {error}")
if failures:
    raise SystemExit("\n".join(failures))
