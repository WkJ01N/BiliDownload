"""JSON-lines interface shared by the optional Windows and Android engines.

stdin carries one request. stdout only carries public metadata, progress, or
a classified error. Signed media URLs and account cookies stay in the process.
"""
import io
import json
import math
import os
import re
import sys
import time
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


class EngineError(Exception):
    pass


VIDEO = re.compile(r"[\w-]{11}", re.ASCII)
PLAYLIST = re.compile(r"[\w-]{2,128}", re.ASCII)
PAGE_SIZE = 40


def emit(**data):
    print(json.dumps(data, ensure_ascii=True, allow_nan=False), flush=True)


class QuietLogger:
    invalid_session = False

    def warning(self, msg):
        if "cookies are no longer valid" in msg.lower():
            self.invalid_session = True

    def debug(self, msg):
        pass

    error = debug


def classify(message, authenticated):
    text = message.lower()
    if "drm" in text or "digital rights" in text:
        return "Protected"
    if any(term in text for term in ("429", "too many requests", "rate-limit")):
        return "Restricted"
    if "not a bot" in text or "not a robot" in text:
        return "VerifyAccount" if "sign in" in text or "log in" in text else "Restricted"
    if "this live event will begin" in text or "this live event will start" in text:
        return "Live"
    if any(term in text for term in ("members-only", "members only", "join this channel", "membership level", "members of this channel")):
        return "Membership" if authenticated else "SessionRequired"
    if any(term in text for term in ("login", "log in", "sign in", "private video", "cookies are no longer valid")):
        return "SessionExpired" if authenticated else "SessionRequired"
    if any(term in text for term in ("timed out", "network", "connection", "resolve host", "certificate verify", "proxy", "403")):
        return "Network"
    return "Unavailable"


def byte_size(fmt, duration):
    for key in ("filesize", "filesize_approx"):
        if (fmt.get(key) or 0) > 0:
            return int(fmt[key])
    bitrate = fmt.get("tbr") or (fmt.get("abr") if fmt.get("vcodec") == "none" else 0) or 0
    estimate = bitrate * (duration or 0) * 125
    return math.ceil(estimate) if 0 < estimate < 2**60 else None


def compatible(info):
    """One shareable AVC/AAC combination per resolution (short edge for Shorts)."""
    formats = [f for f in info.get("formats", []) if not f.get("has_drm") and f.get("url")
               and f.get("protocol") in ("https", "http_dash_segments", "m3u8_native", "m3u8")]
    tracks = [f for f in formats if f.get("vcodec") == "none" and (f.get("acodec") or "").startswith("mp4a")]
    audio = max(tracks, key=lambda f: (f.get("language_preference") or 0, f.get("abr") or f.get("tbr") or 0), default=None)
    by_height = {}
    for video in formats:
        if video.get("ext") != "mp4" or not (video.get("vcodec") or "").startswith(("avc", "h264")):
            continue
        separate = video.get("acodec") == "none"
        if separate and not audio or not separate and not (video.get("acodec") or "").startswith("mp4a"):
            continue
        height = int(min(video.get("height") or 0, video.get("width") or video.get("height") or 0))
        if height <= 0:
            continue
        parts = [video, audio] if separate else [video]
        sizes = [byte_size(f, info.get("duration")) for f in parts]
        choice = {"height": height, "bytes": sum(sizes) if all(sizes) else None,
                  "format": "+".join(f["format_id"] for f in parts)}
        score = (video.get("protocol") == "https", video.get("fps") or 0, video.get("tbr") or 0)
        if height not in by_height or score > by_height[height][0]:
            by_height[height] = (score, choice, parts)
    return [(by_height[h][1], by_height[h][2]) for h in sorted(by_height)]


def audio_choices(info):
    """Return selectable source tracks, best first, with no private URLs in metadata."""
    rows = []
    for order, fmt in enumerate(info.get("formats") or []):
        if fmt.get("has_drm") or not fmt.get("url") or fmt.get("protocol") not in ("https", "http_dash_segments", "m3u8", "m3u8_native"):
            continue
        name = (fmt.get("acodec") or "").lower()
        codec = next((label for prefix, label in (("mp4a", "AAC"), ("aac", "AAC"), ("opus", "Opus"),
                     ("flac", "FLAC"), ("ec-3", "E-AC-3"), ("eac3", "E-AC-3"), ("mp3", "MP3"))
                     if name.startswith(prefix)), None)
        if not codec:
            continue
        mixed = fmt.get("vcodec") != "none"
        rate = fmt.get("abr") or (fmt.get("tbr") if not mixed else None)
        duration = info.get("duration") or 0
        size = byte_size(fmt, duration)
        drc = bool(fmt.get("dynamic_range_compression")) or "drc" in (str(fmt.get("format_id")) + " " + str(fmt.get("format_note"))).lower()
        row = {"container": fmt.get("ext") or "", "drc": drc, "format": fmt["format_id"], "codec": codec, "bitrate": round(rate or 0),
               "language": fmt.get("language") or "", "channels": fmt.get("audio_channels") or 0,
               "duration": duration, "mixed": mixed, "bytes": size,
               "audioBytes": round(rate * duration * 125) if mixed and rate and duration else None if mixed else size}
        grade = 0 if mixed else (fmt.get("quality") or 0)
        rank = (fmt.get("language_preference") or 0, codec == "FLAC", grade, not drc, order)
        rows.append((rank, row, [fmt]))
    independent = [r for r in rows if not r[1]["mixed"]]
    available = independent or rows
    # Compare bitrates inside a codec. Across codecs follow the extractor's quality order.
    codec_order = {}
    for rank, row, _ in available:
        key = (rank[:-1], row["codec"])
        codec_order[key] = max(codec_order.get(key, -1), rank[-1])
    def score(entry):
        rank, row, _ = entry
        if row["mixed"]:
            return (*rank[:-1], row["bitrate"], -(row["bytes"] or float("inf")), rank[-1])
        return (*rank[:-1], codec_order[(rank[:-1], row["codec"])], row["bitrate"], rank[-1])
    return [(r, tracks) for _, r, tracks in sorted(available, key=score, reverse=True)]


def entry(info, index=1):
    return {"id": info["id"], "title": info.get("title") or info["id"], "index": index,
            "artist": info.get("artist") or "", "album": info.get("album") or "", "trackTitle": info.get("track") or "",
            "cover": "https://i.ytimg.com/vi/" + info["id"] + "/mqdefault.jpg"}


def playlist_page(info, start):
    rows = list(info.get("entries") or [])
    entries = []
    for i, row in enumerate(rows[:PAGE_SIZE], start):
        if not row or not VIDEO.fullmatch(row.get("id") or ""):
            continue
        if row.get("title") in ("[Private video]", "[Deleted video]"):
            continue
        entries.append(entry(row, row.get("playlist_index") or i))
    return {"title": info.get("title", ""), "items": entries,
            "next": start + PAGE_SIZE if len(rows) > PAGE_SIZE else 0}


def checkpoint(info, fmt):
    query = parse_qs(urlsplit(fmt["url"]).query)
    modified = query.get("lmt", [None])[0] or fmt.get("last_modified")
    size = fmt.get("filesize") or query.get("clen", [None])[0]
    if not modified or not size or fmt.get("protocol") != "https":
        return None
    return [info["id"], fmt["format_id"], str(modified), str(size)]


def fetch_tracks(ydl, info, choices, request, work, report):
    audio_only = request.get("mode") == "audio"
    if audio_only:
        selected = next((c for c in choices if c[0]["format"] == request.get("format")
                         and c[0]["mixed"] == request.get("mixed", False)), None)
        if not selected:
            raise EngineError("TrackChanged")
        choice, formats = selected
    else:
        candidates = [c for c in choices if c[0]["height"] <= request["height"]]
        if not candidates:
            raise EngineError("NoFormat")
        choice, formats = next((c for c in candidates if c[0]["format"] == request.get("format")), candidates[-1])
    for index, fmt in enumerate(formats):
        # Both filenames are fixed. A changed or unverifiable stream starts
        # fresh even when the old signed URL happened to use the same itag.
        track = work / ("audio.track" if index or audio_only else "video.track")
        marker = track.with_suffix(".checkpoint")
        identity = checkpoint(info, fmt)
        try:
            previous = json.loads(marker.read_text()) if marker.is_file() else None
        except (OSError, ValueError):
            previous = None
        if not identity or identity != previous:
            for old in work.glob(track.name + "*"):
                if old.is_file():
                    old.unlink()
        if identity and track.is_file() and track.stat().st_size != int(identity[-1]):
            track.unlink()
        staging = marker.with_suffix(".new")
        staging.write_text(json.dumps(identity))
        staging.replace(marker)
        last = 0.0

        def progress(status):
            nonlocal last
            if time.monotonic() - last < .5 and status.get("status") != "finished":
                return
            last = time.monotonic()
            total = status.get("total_bytes") or status.get("total_bytes_estimate") or 0
            fraction = min(1, status.get("downloaded_bytes", 0) / total) if total else 0
            report(progress=(index + fraction) / len(formats))

        # yt-dlp's downloader owns HTTPS chunking and HLS/DASH fragments.
        # Operate on exactly one selected track, without running postprocessors.
        data = {k: v for k, v in info.items() if k not in ("requested_formats", "requested_downloads")}
        data.update(fmt)
        downloader_cls = ydl_module.downloader.get_suitable_downloader(data, ydl.params)
        downloader = downloader_cls(ydl, ydl.params)
        downloader.add_progress_hook(progress)
        ok, _ = downloader.download(str(track), data)
        if not ok or not track.is_file() or not track.stat().st_size:
            raise EngineError("Network")
    return {"audio": audio_only or len(formats) == 2, "format": choice["format"], "height": choice.get("height", 0), "tags": entry(info)}


def execute(request, qjs, work, state=None):
    if request.get("platform") == "x":
        # Isolated Windows Python omits the installed script directory from sys.path.
        script_dir = str(Path(__file__).resolve().parent)
        if script_dir not in sys.path:
            sys.path.append(script_dir)
        from xpost import XError, execute_x
        try:
            return execute_x(request, work, ydl_module.YoutubeDL, QuietLogger(), emit)
        except XError as error:
            raise EngineError(str(error)) from None
    operation = request["op"]
    identifier = request["id"]
    listing = operation == "playlist"
    if operation not in ("playlist", "inspect", "fetch") or not (PLAYLIST if listing else VIDEO).fullmatch(identifier):
        raise EngineError("InvalidLink")
    url = ("https://www.youtube.com/playlist?list=" if listing else "https://www.youtube.com/watch?v=") + identifier
    if request.get("music"):
        url = url.replace("www.youtube.com", "music.youtube.com")
    audio_only = request.get("mode") == "audio"
    start = max(1, int(request.get("start", 1)))
    cache_dir = request.get("cacheDir") or os.environ.get("YT_DLP_CACHE_DIR") or False
    opts = {"quiet": True, "no_warnings": True, "logger": QuietLogger(), "cachedir": cache_dir,
            "socket_timeout": 12, "retries": 2, "extractor_retries": 1, "fragment_retries": 2,
            "skip_unavailable_fragments": False, "fixup": "never", "hls_prefer_native": True,
            "js_runtimes": {"quickjs": {"path": qjs}}, "remote_components": set(),
            "extract_flat": "in_playlist", "playlist_items": f"{start}:{start + PAGE_SIZE}",
            "noplaylist": not listing}
    cookies = request.get("cookies")
    cookie_key = hash(cookies) if cookies else None
    if state is not None and state.get("cookie_key") != cookie_key:
        state["cookie_key"] = cookie_key
        state["prefer_auth"] = False
    prefer_auth = bool(state and state.get("prefer_auth"))
    attempts = (True,) if (audio_only or prefer_auth) and cookies else (False, True)
    for authenticated in attempts:
        try:
            with ydl_module.YoutubeDL(opts) as ydl:
                if authenticated:
                    ydl.cookiejar.load(io.StringIO(cookies), ignore_expires=False)
                info = ydl.extract_info(url, download=False)
                if authenticated and opts["logger"].invalid_session:
                    raise EngineError("SessionExpired")
                if listing:
                    return playlist_page(info, start)
                if info.get("live_status") == "post_live":
                    raise EngineError("ReplayProcessing")
                if info.get("live_status") in ("is_live", "is_upcoming"):
                    raise EngineError("Live")
                choices = audio_choices(info) if audio_only else compatible(info)
                if operation == "inspect" and not audio_only and not choices:
                    audio_list = audio_choices(info)
                    if audio_list:
                        return dict(entry(info), variants=[], audioVariants=[c for c, _ in audio_list])
                if not choices:
                    if info.get("availability") in ("subscriber_only", "needs_auth", "private"):
                        raise EngineError("Membership" if authenticated else "SessionRequired")
                    if any(f.get("has_drm") for f in info.get("formats", [])):
                        raise EngineError("Protected")
                    raise EngineError("NoAudio" if audio_only else "NoFormat")
                if operation == "inspect":
                    extra = ({"videoVariants": [c for c, _ in compatible(info)]} if audio_only
                             else {"audioVariants": [c for c, _ in audio_choices(info)]})
                    return dict(entry(info), variants=[choice for choice, _ in choices], **extra)
                return fetch_tracks(ydl, info, choices, request, work, emit)
        except Exception as exc:
            kind = str(exc) if isinstance(exc, EngineError) else classify(str(exc), authenticated)
            if authenticated and opts["logger"].invalid_session and kind in ("Membership", "SessionRequired", "VerifyAccount"):
                kind = "SessionExpired"
            if kind in ("SessionRequired", "VerifyAccount") and cookies and not authenticated:
                if state is not None:
                    state["prefer_auth"] = True
                continue
            raise EngineError(kind) from None


def run_worker(qjs):
    worker_state = {"cookie_key": None, "prefer_auth": False}
    emit(ready=True)
    while True:
        raw = sys.stdin.buffer.readline(512 * 1024)
        if not raw:
            break
        try:
            request = json.loads(raw)
            if request.get("op") == "shutdown":
                break
            emit(result=execute(request, qjs, Path.cwd(), worker_state))
        except EngineError as error:
            emit(error=str(error))
        except Exception:
            if os.environ.get("BILIDOWNLOAD_TEST_DIAGNOSTICS") == "1":
                __import__("traceback").print_exc()
            emit(error="Engine")


if __name__ == "__main__":
    try:
        sys.path.insert(0, sys.argv[1])  # The pinned yt-dlp zipapp, including EJS.
        import yt_dlp as ydl_module
        if len(sys.argv) >= 4 and sys.argv[3] == "--worker":
            run_worker(sys.argv[2])
        else:
            request = json.loads(sys.stdin.buffer.readline(512 * 1024))
            emit(result=execute(request, sys.argv[2], Path.cwd()))
    except EngineError as error:
        emit(error=str(error))
    except Exception:
        if os.environ.get("BILIDOWNLOAD_TEST_DIAGNOSTICS") == "1":
            __import__("traceback").print_exc()
        emit(error="Engine")
