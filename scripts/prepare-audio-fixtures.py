"""Generate local test samples, with optional ten-minute Android benchmarks."""
import subprocess
import sys
import tempfile
from pathlib import Path

exe, destination = sys.argv[1:3]
out = Path(destination)
out.mkdir(parents=True, exist_ok=True)
def media(name, *args):
    subprocess.run([exe, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args, str(out / name)], check=True)

tone = ["-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=48000", "-ac", "2"]
media("aac.m4a", *tone, "-c:a", "aac", "-b:a", "128k")
media("opus.webm", *tone, "-c:a", "libopus")
media("lossless.m4a", *tone, "-c:a", "flac", "-strict", "-2", "-f", "mp4")
media("dolby.m4a", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=5.1", "-t", "1", "-c:a", "eac3", "-f", "mp4")
media("mixed.mp4", "-f", "lavfi", "-i", "color=size=32x32:duration=1", "-i", str(out / "aac.m4a"), "-c:v", "mpeg4", "-c:a", "copy", "-shortest")
encoders = subprocess.check_output([exe, "-hide_banner", "-encoders"], text=True)
available = {line.split()[1] for line in encoders.splitlines() if line.startswith(" V")}
preferred = ("libopenh264", "libx264") if sys.platform == "win32" else ("libx264", "libopenh264")
h264 = next((encoder for encoder in preferred if encoder in available), None)
if h264 is None:
    raise SystemExit("FFmpeg requires libx264 or libopenh264 to generate the test video.")
media("animation.mp4", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=15:duration=1",
      "-c:v", h264, "-pix_fmt", "yuv420p", "-an")

if "--long" in sys.argv[3:]:
    with tempfile.TemporaryDirectory(dir=out.resolve()) as temp:
        seed = Path(temp) / "seed.mp4"
        media(seed, "-f", "lavfi", "-i", "testsrc2=size=960x540:rate=24:duration=8",
              "-c:v", h264, "-b:v", "900k", "-pix_fmt", "yuv420p", "-an")
        media("perf-video.mp4", "-stream_loop", "-1", "-i", str(seed), "-t", "600", "-c:v", "copy")
    media("perf-audio.m4a", "-f", "lavfi", "-i", "sine=frequency=440:duration=600:sample_rate=48000",
          "-ac", "2", "-c:a", "aac", "-b:a", "160k")
