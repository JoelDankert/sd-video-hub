import base64
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import app


class OfflineManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.old = (app.DATA, app.LIB, app.STAGE, app.THUMBS, app.DB, app.CUTLIST)
        app.DATA, app.LIB, app.STAGE, app.THUMBS = root, root / "library", root / "staging", root / "thumbs"
        app.DB, app.CUTLIST = root / "state.sqlite3", root / "library" / ".flight-skip.json"
        app.LIB.mkdir(); app.STAGE.mkdir(); app.THUMBS.mkdir()
        app.db().close()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), app.H)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.password = patch.dict(os.environ, {"SDH_PASSWORD": "test-only-password"})
        self.password.start()
        self.config = patch.object(app, "load_config", return_value=json.loads((Path(__file__).parent / "config.example.json").read_text()))
        self.config.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.password.stop()
        self.config.stop()
        app.DATA, app.LIB, app.STAGE, app.THUMBS, app.DB, app.CUTLIST = self.old
        self.tmp.cleanup()

    def request(self, auth=True):
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}/api/offline-manifest")
        if auth:
            token = base64.b64encode(b"mobile:test-only-password").decode()
            request.add_header("Authorization", "Basic " + token)
        return urllib.request.urlopen(request)

    def test_manifest_requires_authentication(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.request(False)
        self.assertEqual(caught.exception.code, 401)

    def test_manifest_contains_only_safe_urls_and_metadata(self):
        media = app.LIB / "flight one.mp4"; media.write_bytes(b"video")
        thumb = app.THUMBS / "flight.jpg"; thumb.write_bytes(b"jpg")
        with app.db() as connection:
            connection.execute("INSERT INTO sessions(id,label,status,created) VALUES('s','S','done',0)")
            connection.execute("INSERT INTO files(session,source,staging,output,thumb,name,size,status) VALUES(?,?,?,?,?,?,?,?)",
                               ("s", "", "", str(media), str(thumb), media.name, 5, "done"))
        data = json.load(self.request())
        self.assertEqual(data["schema"], 1)
        self.assertIn("/", [entry["url"] for entry in data["entries"]])
        self.assertIn("/api/state", [entry["url"] for entry in data["entries"]])
        for entry in data["entries"]:
            self.assertTrue(entry["url"].startswith("/"))
            self.assertNotIn("..", entry["url"])
            self.assertNotIn(self.tmp.name, entry["url"])
            self.assertIsInstance(entry["size"], int)
            self.assertTrue(entry["mime"] and entry["version"])


if __name__ == "__main__":
    unittest.main()
