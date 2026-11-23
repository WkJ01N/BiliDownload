"""Generate short local media samples used by the Windows and Android audio tests."""
import subprocess
import sys
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
