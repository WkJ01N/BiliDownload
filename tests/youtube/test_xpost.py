import io
import sys
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "youtube"))
import bridge
import xpost


POST = "1577719286659006464"
PHOTO = {"type": "photo", "id_str": "11", "media_url_https": "https://pbs.twimg.com/media/image.jpg?name=small"}
MOVIE = {"type": "video", "media_key": "7_22", "original_info": {"width": 1280, "height": 720},
         "video_info": {"duration_millis": 10000, "variants": [
             {"content_type": "video/mp4", "bitrate": b, "url": f"https://video.twimg.com/test/{w}x{h}/file.mp4?tag=private"}
             for w, h, b in [(1280, 720, 2000000), (640, 360, 800000)]]}}
GIF = {"type": "animated_gif", "id_str": "33", "original_info": {"width": 200, "height": 350},
       "video_info": {"variants": [{"content_type": "video/mp4", "url": "https://video.twimg.com/tweet_video/A.mp4"}]}}


def tweet(items=(), **legacy):
    return {"__typename": "Tweet", "rest_id": POST, "legacy": dict(
        {"full_text": "第一行\n第二行", "created_at": "Wed Oct 05 17:56:05 +0000 2022",
         "extended_entities": {"media": list(items)}}, **legacy),
        "core": {"user_results": {"result": {"core": {"name": "Name", "screen_name": "user"}}}}}


class XPostTests(unittest.TestCase):
    def run_request(self, responses, **extra):
        req = dict({"platform": "x", "op": "inspect", "id": POST}, **extra)
        module = MagicMock()
        with patch.object(xpost, "read_tweet", side_effect=responses) as lookup, \
                patch.object(xpost, "load_session") as load, patch.object(bridge, "ydl_module", module, create=True):
            result = bridge.execute(req, "", Path.cwd())
        return result, lookup, load

    def test_mixed_post_preserves_attachment_indices_and_full_text(self):
        root = tweet([PHOTO, MOVIE, GIF], quoted_status_id_str="42")
        root["note_tweet"] = {"note_tweet_results": {"result": {"text": "这是一段长文\r\n🙂"}}}
        root["quoted_status_result"] = {"result": tweet([dict(PHOTO, id_str="99")])}
        data, _, session = self.run_request([root], cookies="saved")
        session.assert_not_called()
        self.assertEqual(data["text"], "这是一段长文\r\n🙂")
        self.assertFalse(data["truncated"])
        self.assertEqual(data["createdAt"], "2022-10-05T17:56:05+00:00")
        self.assertEqual(data["handle"], "user")
        self.assertEqual(data["quotedUrl"], "https://x.com/i/status/42")
        self.assertEqual([(i["id"], i["index"], i["kind"]) for i in data["items"]],
                         [("11", 1, "image"), ("7_22", 2, "video"), ("33", 3, "gif")])
        self.assertEqual([r["height"] for r in data["items"][1]["variants"]], [360, 720])
        self.assertFalse(data["items"][2]["audio"])
        self.assertNotIn("twimg.com", str(data))

    def test_repost_visibility_and_text_only(self):
        share = tweet([dict(PHOTO, id_str="wrapper")], retweeted_status_result={"result": tweet()})
        share["rest_id"] = "999"
        result, _, _ = self.run_request([{"__typename": "TweetWithVisibilityResults", "tweet": share}])
        self.assertEqual(result["id"], POST)
        self.assertEqual(result["items"], [])
        self.assertEqual(result["text"], "第一行\n第二行")
        partial = tweet()
        partial["note_tweet"] = {"note_tweet_results": {}}
        self.assertTrue(self.run_request([partial])[0]["truncated"])
        with self.assertRaisesRegex(bridge.EngineError, "Unavailable"):
            self.run_request([{}])

    def test_retries_session_once_only_for_access(self):
        denied = {"__typename": "TweetUnavailable", "reason": "Protected"}
        with self.assertRaisesRegex(bridge.EngineError, "SessionRequired"):
            self.run_request([denied])
        _, calls, load = self.run_request([denied, tweet()], cookies="saved")
        self.assertEqual(calls.call_count, 2)
        load.assert_called_once()
        for responses, expected in [([denied, denied], "Forbidden"),
                                    ([RuntimeError("rate-limit exceeded")], "Restricted"),
                                    ([{"reason": "Deleted"}], "Deleted")]:
            with self.subTest(expected), self.assertRaisesRegex(bridge.EngineError, expected):
                self.run_request(responses, cookies="saved")
        for invalid in ("-1", "123?foo", "0", "1" * 21):
            with self.subTest(invalid), self.assertRaisesRegex(bridge.EngineError, "InvalidLink"):
                self.run_request([], id=invalid)

    def test_refresh_uses_attachment_identity_and_capped_variant(self):
        with patch.object(xpost, "download", return_value={"bytes": 3}) as downloader:
            result, _, _ = self.run_request([tweet([GIF, MOVIE, PHOTO])], op="fetch", attachment="7_22", height=540)
            self.assertEqual(result, {"bytes": 3})
            self.assertIn("640x360", downloader.call_args.args[0])
            self.run_request([tweet([PHOTO])], op="fetch", attachment="11")
            self.assertEqual(downloader.call_args.args[:2], ("https://pbs.twimg.com/media/image.jpg?name=orig", ".jpg"))
            with self.assertRaisesRegex(bridge.EngineError, "TrackChanged"):
                self.run_request([tweet([GIF])], op="fetch", attachment="7_22")

    def test_cdn_url_is_typed_and_scoped(self):
        for url in ("http://pbs.twimg.com/media/a.jpg", "https://pbs.twimg.com.evil.test/photo.jpg",
                    "https://pbs.twimg.com@127.0.0.1/photo.jpg", "https://pbs.twimg.com:8080/photo.jpg"):
            self.assertIsNone(xpost.photo_source(dict(PHOTO, media_url_https=url)))
        for url in ("https://video.twimg.com.evil.test/a.mp4", "https://example.com/"):
            with self.assertRaisesRegex(xpost.XError, "Network"):
                xpost.CdnRedirects("video.twimg.com").redirect_request(None, None, 302, "", {}, url)

    def test_animation_and_explicit_audio_source_can_use_original_dimensions(self):
        url = "https://video.twimg.com/ext_tw_video/1/pu/vid/1920x1440/large.mp4"
        large = {"content_type": "video/mp4", "url": url}
        for media in (GIF, MOVIE):
            attachment = dict(media, video_info={"variants": [large]})
            post = tweet([attachment])
            item, = self.run_request([post])[0]["items"]
            request = {"op": "fetch", "attachment": item["id"]}
            if media is MOVIE:
                request["format"] = item["variants"][0]["format"]
            with patch.object(xpost, "download", return_value={}) as downloader:
                self.run_request([post], **request)
                self.assertEqual(downloader.call_args.args[0], url)

    def test_unavailable_video_leaves_other_attachments_playable(self):
        blocked = dict(MOVIE, ext_media_availability={"status": "Unavailable"})
        only_hls = dict(MOVIE, video_info={"variants": [{"content_type": "application/x-mpegURL",
                                                         "url": "https://video.twimg.com/test/master.m3u8"}]})
        for media in (blocked, only_hls):
            with self.subTest(media=media):
                root = tweet([media, GIF, PHOTO])
                data, _, _ = self.run_request([root])
                blocked_row, gif, photo = data["items"]
                self.assertEqual(blocked_row["variants"], [])
                self.assertFalse(blocked_row["audio"])
                self.assertEqual(gif["variants"][0]["height"], 200)
                self.assertEqual(photo["index"], 3)
                with self.assertRaisesRegex(bridge.EngineError, "NoFormat"):
                    self.run_request([root], op="fetch", attachment=blocked_row["id"])

    def test_binary_transfer_stages_and_checks_length(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(xpost, "build_opener") as factory:
            work = Path(temp)
            jpeg = b"\xff\xd8\xff"
            for n, payload in [(3, jpeg), (9, jpeg), (3, b"abc")]:
                body = io.BytesIO(payload)
                body.headers = Message()
                body.headers["Content-Length"] = str(n)
                body.headers["Content-Type"] = "image/jpeg"
                factory.return_value.open.return_value = body
                args = ("https://pbs.twimg.com/media/test.jpg", ".jpg", work, lambda **_: None)
                if n == len(payload) and payload == jpeg:
                    self.assertEqual(xpost.download(*args), {"bytes": 3, "extension": ".jpg"})
                    self.assertEqual((work / "x.track").read_bytes(), jpeg)
                    (work / "x.track").unlink()
                else:
                    with self.assertRaisesRegex(xpost.XError, "Network"):
                        xpost.download(*args)
                    self.assertFalse((work / "x.track").exists())
                self.assertFalse((work / "x.track.part").exists())

    def test_signatures_match_the_saved_file_type(self):
        for ext, signature in [(".jpg", b"\xff\xd8\xff"), (".png", b"\x89PNG\r\n\x1a\n"),
                               (".webp", b"RIFFxxxxWEBP"), (".mp4", b"\x00\x00\x00\x20ftypisom")]:
            self.assertTrue(xpost.media_signature(signature, ext))
            self.assertFalse(xpost.media_signature(b"<html>Not authorized</html>", ext))
