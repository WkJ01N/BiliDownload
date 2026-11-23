"""Build the optional engine from pinned upstream assets. No host Python at runtime."""
import hashlib
import json
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".build/youtube"
CACHE.mkdir(parents=True, exist_ok=True)
PINS = json.loads((ROOT / "packaging/youtube.json").read_text())
OUT = ROOT / "artifacts/components"

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def get(name):
    path = CACHE / name
    pin = PINS[name]
    if not path.exists() or digest(path) != pin["sha256"]:
        with tempfile.NamedTemporaryFile(dir=CACHE, delete=False) as tmp:
            with urllib.request.urlopen(pin["url"], timeout=90) as remote:
                shutil.copyfileobj(remote, tmp)
        if digest(Path(tmp.name)) != pin["sha256"]:
            raise ValueError("Checksum mismatch: " + name)
        Path(tmp.name).replace(path)
    return path

def licenses():
    result = {}
    for package in ("yt-dlp", "yt-dlp-ejs", "quickjs-ng"):
        with tarfile.open(get(package + ".tar.gz")) as archive:
            for member in archive.getmembers():
                parts = Path(member.name).parts
                if member.isfile() and (parts[-1].lower().startswith(("license", "copying"))):
                    result[package + "/" + "/".join(parts[1:])] = archive.extractfile(member).read()
    return result

def write_zip(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)

def windows():
    with zipfile.ZipFile(get("python-embed.zip")) as archive:
        entries = {n: archive.read(n) for n in archive.namelist()}
    entries["qjs.exe"] = get("qjs-windows-x86_64.exe").read_bytes()
    entries["yt-dlp"] = get("yt-dlp").read_bytes()
    entries.update({"licenses/" + k: v for k, v in licenses().items()})
    path = OUT / "youtube-win-x64.zip"
    write_zip(path, entries)
    size = sum(map(len, entries.values()))
    manifest = ROOT / "src/BiliDownload.Windows/Assets/components.json"
    packages = [p for p in json.loads(manifest.read_text(encoding="utf-8-sig")) if p["Id"] != "youtube"]
    package = dict(Id="youtube", Name="YouTube 下载组件", Version="2026.10.26-1",
                   Url="https://github.com/WkJ01N/BiliDownload/releases/download/v0.2.0/youtube-win-x64.zip",
                   Sha256=digest(path), DownloadBytes=path.stat().st_size, InstalledBytes=size, EntryPoint="python.exe")
    if "--write-manifest" in sys.argv:
        manifest.write_text(json.dumps(packages + [package], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif package not in json.loads(manifest.read_text(encoding="utf-8-sig")):
        raise ValueError("Windows engine does not match pinned component manifest")
    (OUT / "youtube-component.json").write_text(json.dumps(package, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(path, size)

def android():
    gen = ROOT / "android/youtube/build/generated/engine"
    raw = gen / "res/raw"
    raw.mkdir(parents=True, exist_ok=True)
    # Overrides the wrapper's default raw resource with the verified zipapp.
    shutil.copyfile(get("yt-dlp"), raw / "ytdlp")
    assets = gen / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "youtube/bridge.py", assets / "bridge.py")
    for name, data in licenses().items():
        dest = assets / "licenses" / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    shutil.copyfile(get("android-LICENSE.txt"), assets / "licenses/Android-GPL.txt")

def sources():
    names = [n for n in PINS if n.endswith((".tar.gz", ".tar.xz", ".jar", ".txt", ".md"))]
    entries = {n: get(n).read_bytes() for n in names}
    for folder in ("android/youtube/src", "android/protocol", "youtube"):
        for file in (ROOT / folder).rglob("*"):
            if file.is_file() and "__pycache__" not in file.parts:
                entries[file.relative_to(ROOT).as_posix()] = file.read_bytes()
    for file in ("android/youtube/build.gradle.kts", "scripts/prepare-youtube.py", "LICENSE"):
        entries[file] = (ROOT / file).read_bytes()
    entries["dependencies.json"] = (ROOT / "packaging/youtube.json").read_bytes()
    entries["THIRD_PARTY_NOTICES.md"] = (ROOT / "THIRD_PARTY_NOTICES.md").read_bytes()
    write_zip(OUT / "youtube-component-sources.zip", entries)

if __name__ == "__main__":
    {"windows": windows, "android": android, "sources": sources}[sys.argv[1]]()
