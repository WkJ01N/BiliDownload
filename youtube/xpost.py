"""X post lookup using the packaged Twitter extractor's first-party transport."""
import hashlib
import io
import re
import time
from email.utils import parsedate_to_datetime
from http.client import HTTPException
from pathlib import Path
from urllib.error import URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class XError(Exception):
    pass


POST_ID = re.compile(r"[1-9][0-9]{0,19}")
PHOTO_TYPES = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


def cdn(url, host):
    try:
        parts = urlsplit(url or "")
        return parts.scheme == "https" and parts.netloc.lower() in (host, host + ":443") and bool(parts.path)
    except ValueError:
        return False


def x_error(error, signed):
    if isinstance(error, XError):
        return str(error)
    cause = getattr(error, "cause", None) or error
    code = getattr(cause, "status", None) or getattr(cause, "code", None)
    if code == 429:
        return "Restricted"
    if code == 401:
        return "SessionExpired" if signed else "SessionRequired"
    if code == 403:
        return "Forbidden" if signed else "SessionRequired"
    if code in (404, 410):
        return "Unavailable"
    message = str(error).lower()
    if any(word in message for word in ("rate limit", "rate-limit", "too many requests")):
        return "Restricted"
    if any(word in message for word in ("authenticat", "log in", "login", "sign in")):
        return "SessionExpired" if signed else "SessionRequired"
    if isinstance(cause, (URLError, OSError)) or code or any(word in message for word in (
            "network", "connection", "timed out", "certificate verify", "unable to download")):
        return "Network"
    return "Unavailable"


def read_tweet(ydl, post_id):
    from yt_dlp.extractor.twitter import TwitterIE
    ie = TwitterIE(ydl)
    data = ie._call_graphql_api(ie._GRAPHQL_ENDPOINT, post_id)
    return (data.get("tweetResult") or {}).get("result") or {}


def load_session(ydl, cookies):
    try:
        ydl.cookiejar.load(io.StringIO(cookies), ignore_expires=False)
    except (ValueError, OSError, AssertionError):
        raise XError("SessionExpired") from None
    for cookie in list(ydl.cookiejar):
        if cookie.domain.lstrip(".").lower() not in ("x.com", "twitter.com"):
            ydl.cookiejar.clear(cookie.domain, cookie.path, cookie.name)
    if not any(cookie.name == "auth_token" for cookie in ydl.cookiejar):
        raise XError("SessionExpired")


def visible(result, signed):
    if result.get("__typename") == "TweetWithVisibilityResults":
        result = result.get("tweet") or {}
    if result.get("legacy"):
        return result
    reason = str(result.get("reason") or "").lower()
    notice = ((result.get("tombstone") or {}).get("text") or {}).get("text", "").lower()
    if reason in ("nsfwloggedout", "nsfwviewerhasnostatedage", "protected") or any(
            word in notice for word in ("protected", "authorized", "authorised", "sign in", "log in")):
        raise XError("Forbidden" if signed else "SessionRequired")
    if "deleted" in reason or "deleted" in notice:
        raise XError("Deleted")
    raise XError("Unavailable")


def photo_source(media):
    url = media.get("media_url_https") or ""
    if not cdn(url, "pbs.twimg.com"):
        return None
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query))
    path = re.sub(r":(?:small|medium|large|orig)$", "", parts.path)
    extension = (query.get("format") or path.rsplit(".", 1)[-1]).lower()
    if extension == "jpeg":
        extension = "jpg"
    if extension not in PHOTO_TYPES:
        return None
    query["name"] = "orig"
    return "." + extension, urlunsplit(parts._replace(path=path, query=urlencode(query), fragment=""))


def video_sources(media):
    video = media.get("video_info") or {}
    original = media.get("original_info") or {}
    largest = (media.get("sizes") or {}).get("large") or {}
    rows = [row for row in video.get("variants") or [] if row.get("content_type") == "video/mp4"
            and cdn(row.get("url"), "video.twimg.com")]
    by_height = {}
    for row in rows:
        path = urlsplit(row["url"]).path
        dimensions = re.search(r"/(\d{1,5})x(\d{1,5})/", path)
        width = int(dimensions[1]) if dimensions else row.get("width")
        height = int(dimensions[2]) if dimensions else row.get("height")
        if not (width and height) and len(rows) == 1:
            width = original.get("width") or largest.get("w")
            height = original.get("height") or largest.get("h")
        short_edge = min(width or 0, height or 0)
        if not short_edge:
            continue
        bitrate = row.get("bitrate") or row.get("bit_rate") or 0
        if short_edge in by_height and bitrate <= by_height[short_edge][0]:
            continue
        duration_ms = video.get("duration_millis") or 0
        size = round(bitrate * duration_ms / 8000) if bitrate and duration_ms else None
        choice = {"height": short_edge, "bytes": size, "format": f"mp4-{width}x{height}-{bitrate}"}
        by_height[short_edge] = bitrate, choice, row["url"]
    return [(variant, source) for _, variant, source in (by_height[h] for h in sorted(by_height))]


def post_info(result, requested, signed):
    root = visible(result, signed)
    if root["legacy"].get("retweeted_status_result"):
        root = visible(root["legacy"]["retweeted_status_result"].get("result") or {}, signed)
    legacy = root["legacy"]
    post_id = str(root.get("rest_id") or legacy.get("id_str") or requested)
    if not POST_ID.fullmatch(post_id):
        raise XError("Unavailable")
    author = (((root.get("core") or {}).get("user_results") or {}).get("result") or {})
    person = {**(author.get("legacy") or {}), **(author.get("core") or {})}
    note = (((root.get("note_tweet") or {}).get("note_tweet_results") or {}).get("result") or {})
    body = note.get("text") or legacy.get("full_text") or legacy.get("text") or ""
    created = legacy.get("created_at") or ""
    try:
        created = parsedate_to_datetime(created).isoformat()
    except (ValueError, TypeError, OverflowError):
        created = ""
    quote = str(legacy.get("quoted_status_id_str") or "")
    data = {"id": post_id, "author": person.get("name") or "", "handle": person.get("screen_name") or "",
            "text": body, "createdAt": created, "url": "https://x.com/i/status/" + post_id,
            "truncated": bool(not note.get("text") and (legacy.get("truncated") or root.get("note_tweet") or root.get("article"))),
            "items": []}
    if POST_ID.fullmatch(quote):
        data["quotedUrl"] = "https://x.com/i/status/" + quote
    sources = {}
    media_rows = (legacy.get("extended_entities") or legacy.get("entities") or {}).get("media") or []
    for index, media in enumerate(media_rows, 1):
        kind = {"photo": "image", "video": "video", "animated_gif": "gif"}.get(media.get("type"))
        if not kind:
            continue
        key = str(media.get("media_key") or media.get("id_str") or media.get("id") or "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,96}", key):
            path = urlsplit(media.get("media_url_https") or "").path
            if not path:
                continue
            key = hashlib.sha256(path.encode()).hexdigest()[:24]
        item = {"id": key, "index": index, "kind": kind, "variants": [], "extension": ".mp4"}
        available = (media.get("ext_media_availability") or {}).get("status") in (None, "Available")
        if kind == "image":
            photo = photo_source(media) if available else None
            if not photo:
                continue
            item["extension"], sources[key] = photo
        else:
            sources[key] = video_sources(media) if available else []
            item["variants"] = [variant for variant, _ in sources[key]]
            video = media.get("video_info") or {}
            item["audio"] = bool(sources[key]) and kind == "video" and video.get("has_audio", True)
            item["duration"] = (video.get("duration_millis") or 0) / 1000
        data["items"].append(item)
    if not data["items"] and not body:
        raise XError("Unavailable")
    return data, sources


class CdnRedirects(HTTPRedirectHandler):
    def __init__(self, host):
        self.host = host

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not cdn(newurl, self.host):
            raise XError("Network")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, extension, work, report):
    host = "video.twimg.com" if extension == ".mp4" else "pbs.twimg.com"
    if not cdn(url, host):
        raise XError("Network")
    # CDN requests are independent of the post lookup session, including on redirects.
    opener = build_opener(CdnRedirects(host))
    part = work / "x.track.part"
    try:
        with opener.open(Request(url), timeout=15) as response:
            if response.headers.get_content_type() not in (PHOTO_TYPES.get(extension[1:], "video/mp4"), "application/octet-stream"):
                raise XError("Network")
            length = int(response.headers.get("Content-Length") or 0)
            size = 0
            last = 0
            with part.open("wb") as output:
                while block := response.read(256 * 1024):
                    if not size and not media_signature(block, extension):
                        raise XError("Network")
                    output.write(block)
                    size += len(block)
                    if 0 < size < length and time.monotonic() - last >= .5:
                        report(progress=min(size / length, 1))
                        last = time.monotonic()
        if not size or length and size != length:
            raise XError("Network")
        part.replace(work / "x.track")
    except (OSError, HTTPException, ValueError) as error:
        raise XError("Restricted" if getattr(error, "code", None) == 429 else "Network") from None
    finally:
        part.unlink(missing_ok=True)
    report(progress=1.0)
    return {"extension": extension, "bytes": size}


def media_signature(block, extension):
    if extension == ".mp4":
        return block[4:8] == b"ftyp"
    if extension == ".jpg":
        return block.startswith(b"\xff\xd8\xff")
    if extension == ".png":
        return block.startswith(b"\x89PNG\r\n\x1a\n")
    return extension == ".webp" and block.startswith(b"RIFF") and block[8:12] == b"WEBP"


def execute_x(request, work, client_factory, logger, report):
    op, post_id = request.get("op"), request.get("id")
    if op not in ("inspect", "fetch") or not isinstance(post_id, str) or not POST_ID.fullmatch(post_id):
        raise XError("InvalidLink")
    cookies = request.get("cookies")
    options = {"quiet": True, "no_warnings": True, "logger": logger, "cachedir": False,
               "socket_timeout": 12, "extractor_retries": 0}
    for signed in ([False, True] if cookies else [False]):
        try:
            with client_factory(options) as ydl:
                if signed:
                    load_session(ydl, cookies)
                data, sources = post_info(read_tweet(ydl, post_id), post_id, signed)
            break
        except Exception as error:
            kind = x_error(error, signed)
            if kind in ("SessionRequired", "Unavailable") and cookies and not signed:
                continue
            raise XError(kind) from None
    if op == "inspect":
        return data
    item = next((i for i in data["items"] if i["id"] == request.get("attachment")), None)
    if not item:
        raise XError("TrackChanged")
    source = sources[item["id"]]
    if item["kind"] != "image":
        requested_format = request.get("format")
        cap = request.get("height") or (None if requested_format else 1080)
        options = source if item["kind"] == "gif" or cap is None else [
            (variant, url) for variant, url in source if variant["height"] <= cap
        ]
        if not options:
            raise XError("NoFormat")
        if requested_format:
            source = next((url for v, url in options if v["format"] == requested_format), None)
            if not source:
                raise XError("TrackChanged")
        else:
            source = options[-1][1]
    return download(source, item["extension"], Path(work), report)
