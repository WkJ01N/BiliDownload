"""Package exact third-party source and build instructions for the audio tools."""
import hashlib
import json
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
pins = json.loads((root / "packaging/audio.json").read_text())
cache = root / ".build/audio"
cache.mkdir(parents=True, exist_ok=True)
out = root / "artifacts/components"
out.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out / "audio-component-sources.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for name, pin in pins.items():
        source = cache / name
        if not source.exists() or hashlib.sha256(source.read_bytes()).hexdigest() != pin["sha256"]:
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp:
                with urllib.request.urlopen(pin["url"], timeout=90) as response:
                    shutil.copyfileobj(response, temp)
            downloaded = Path(temp.name)
            if hashlib.sha256(downloaded.read_bytes()).hexdigest() != pin["sha256"]:
                raise ValueError("Checksum mismatch: " + name)
            downloaded.replace(source)
        if name in ("taglib-sharp.tar.gz", "android-taglib.tar.gz"):
            # Keep matching library source and build files; the upstream test suites include large media albums.
            with tarfile.open(source) as upstream:
                for entry in upstream:
                    rel = "/".join(entry.name.split("/")[1:])
                    if not entry.isfile() or rel.startswith("src/androidTest/assets/"):
                        continue
                    if rel.startswith("src/TaglibSharp.Tests/") and not rel.endswith((".cs", ".csproj", ".md", ".txt")):
                        continue
                    if rel == "local.properties":
                        continue
                    archive.writestr("upstream/" + name.removesuffix(".tar.gz") + "/" + rel, upstream.extractfile(entry).read())
        else:
            archive.write(source, "upstream/" + name)
    files = [root / p for p in ("scripts/build-android-audio.sh", "scripts/prepare-audio-sources.py", "packaging/audio.json", "android/audio/build.gradle.kts", "THIRD_PARTY_NOTICES.md", "LICENSE")]
    files.extend((root / "android/audio/src").rglob("*"))
    files.append(root / "android/protocol/io/github/bilidownload/bridge/IAudioProcessor.aidl")
    for path in files:
        if path.is_file():
            archive.write(path, path.relative_to(root).as_posix())
