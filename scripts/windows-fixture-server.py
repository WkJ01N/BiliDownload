"""Loopback-only fixture API used by the renamed QA app; never contacts Bilibili."""
import http.server
import json
import pathlib
import sys
import sqlite3
import urllib.parse

root = pathlib.Path(sys.argv[1])


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/qa/tasks":
            with sqlite3.connect(root / "app" / "qa-data" / "tasks.db") as db:
                tasks = [json.loads(row[0]) for row in db.execute("select data from tasks")]
            response = json.dumps(tasks).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
            return
        if path == "/media":
            data = (root / "video.mp4").read_bytes()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        mode = (root / "mode.txt").read_text().strip() if (root / "mode.txt").exists() else "ok"
        if mode == "restricted" and path.endswith("/view"):
            self.send_response(412)
            self.end_headers()
            return
        if path.endswith("/nav"):
            data = {"isLogin": False, "wbi_img": {"img_url": "https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png", "sub_url": "https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}
        elif path.endswith("/qrcode/generate"):
            data = {"url": "https://www.bilibili.com/", "qrcode_key": "offline-qa"}
        elif path.endswith("/qrcode/poll"):
            data = {"code": 86101}
        elif path.endswith("/view"):
            data = {"aid": 1, "bvid": "BV1xx411c7mD", "title": "自动化测试视频", "pic": "", "pages": [{"cid": 101, "part": "第一部分"}, {"cid": 102, "part": "第二部分"}]}
        elif path.endswith("/season"):
            data = {"title": "自动化测试剧集", "cover": "", "episodes": [{"id": 201, "aid": 1, "cid": 101, "title": "第一集"}, {"id": 202, "aid": 1, "cid": 102, "title": "第二集"}]}
        elif path.startswith("/short"):
            self.send_response(302)
            self.send_header("Location", "https://www.bilibili.com/video/BV1xx411c7mD?p=2")
            self.end_headers()
            return
        else:
            data = {"quality": 80, "durl": [{"url": f"http://127.0.0.1:{self.server.server_port}/media"}]}
        response = json.dumps({"code": 0, "data": data, "result": data}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
(root / "endpoint.txt").write_text(f"http://127.0.0.1:{server.server_port}/")
server.serve_forever()
