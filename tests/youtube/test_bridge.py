import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "youtube"))
import bridge

def track(fmt, vcodec, acodec, size, **extra):
    base = dict(format_id=fmt, ext="mp4", url="https://media.example/v?lmt=123&clen=200",
                protocol="https", vcodec=vcodec, acodec=acodec, filesize=size, height=1080, width=1920)
    return dict(base, **extra)

class EngineTests(unittest.TestCase):
    def setUp(self):
        self.video = track("137", "avc1.2", "none", 200)
        self.audio = track("140", "none", "mp4a.40.2", 20)
        self.info = dict(id="jNQXAC9IVRw", title="Example", formats=[self.video, self.audio], duration=12)

    def test_sizes_and_format_filter(self):
        self.info["formats"].extend([dict(self.video, format_id="bad", has_drm=True, fps=100), dict(self.video, vcodec="av01")])
        self.assertEqual(bridge.compatible(self.info)[0][0], {"height":1080,"bytes":220,"format":"137+140"})
        del self.audio["filesize"]
        self.assertIsNone(bridge.compatible(self.info)[0][0]["bytes"])
        self.audio["tbr"] = 10
        self.assertEqual(bridge.compatible(self.info)[0][0]["bytes"], 15200)

    def test_shorts_and_missing_audio(self):
        self.info["formats"] = [dict(self.video, width=720, height=1280)]
        self.assertEqual(bridge.compatible(self.info), [])
        self.info["formats"][0]["acodec"] = "mp4a.40.2"
        self.assertEqual(bridge.compatible(self.info)[0][0]["height"], 720)

    def test_playlist_pages_preserve_positions(self):
        rows = [dict(self.info, playlist_index=i) for i in range(41, 82)]
        rows[2] = None
        page = bridge.playlist_page({"entries":rows}, 41)
        self.assertEqual(page["next"], 81)
        self.assertEqual([i["index"] for i in page["items"]][:4], [41,42,44,45])
        self.assertEqual(bridge.playlist_page({"entries":rows[-1:]},81)["next"], 0)

    def call(self, side_effect, cookies="session"):
        module = MagicMock()
        client = module.YoutubeDL.return_value.__enter__.return_value
        client.extract_info.side_effect = side_effect
        with patch.object(bridge, "ydl_module", module, create=True):
            result = bridge.execute({"op":"inspect","id":"jNQXAC9IVRw","cookies":cookies}, "qjs", Path.cwd())
        return result, client

    def test_public_stays_anonymous(self):
        _, client = self.call([self.info])
        client.cookiejar.load.assert_not_called()
    def test_membership_retries_once_with_session(self):
        result, client = self.call([Exception("Join this channel to get access"), self.info])
        self.assertEqual(result["variants"][0]["format"], "137+140")
        self.assertEqual(client.extract_info.call_count, 2)
        client.cookiejar.load.assert_called_once()
    def test_guest_member_and_expired(self):
        with self.assertRaisesRegex(bridge.EngineError, "SessionRequired"):
            self.call([Exception("Join this channel")], cookies=None)
        with self.assertRaisesRegex(bridge.EngineError, "Membership"):
            self.call([Exception("members-only"), Exception("members-only")])
        with self.assertRaisesRegex(bridge.EngineError, "SessionExpired"):
            self.call([Exception("Sign in"), Exception("Sign in")])
    def test_restriction_and_drm(self):
        for message, code in [("HTTP 429", "Restricted"), ("Sign in to confirm you're not a bot","Restricted"), ("DRM protected","Protected")]:
            with self.assertRaisesRegex(bridge.EngineError, code):
                self.call([Exception(message)])
        with self.assertRaisesRegex(bridge.EngineError, "Live"):
            self.call([dict(self.info, live_status="is_live")])
    def test_refresh_identity(self):
        self.assertEqual(bridge.checkpoint(self.info, self.video), bridge.checkpoint(self.info, dict(self.video,url=self.video["url"]+"&expire=42")))
        self.assertNotEqual(bridge.checkpoint(self.info, self.video), bridge.checkpoint(self.info, self.audio))
        self.assertIsNone(bridge.checkpoint(self.info, dict(self.video, url="https://media.example/v")))

    def test_resume_only_for_the_same_representation(self):
        choices = bridge.compatible(self.info)
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            kept = []
            class Downloader:
                def __init__(self, *args): pass
                def add_progress_hook(self, hook): pass
                def download(self, name, data):
                    kept.append(Path(name + ".part").exists())
                    Path(name).write_bytes(b"x" * data["filesize"])
                    return True, True
            module = MagicMock()
            module.downloader.get_suitable_downloader.return_value = Downloader
            request = {"height":1080, "format":"137+140"}
            with patch.object(bridge, "ydl_module", module, create=True):
                bridge.fetch_tracks(MagicMock(), self.info, choices, request, work, lambda **_: None)
                (work / "video.track").rename(work / "video.track.part")
                (work / "audio.track").rename(work / "audio.track.part")
                self.audio["url"] += "&unused=1"
                self.video["url"] = self.video["url"].replace("lmt=123", "lmt=456")
                bridge.fetch_tracks(MagicMock(), self.info, choices, request, work, lambda **_: None)
        self.assertEqual(kept, [False, False, False, True])
