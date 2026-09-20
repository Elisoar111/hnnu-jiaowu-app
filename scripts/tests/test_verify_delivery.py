import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_delivery import DOWNLOAD_ROOT, Gitee, verify_delivery, version_code_of

TAG = "v1.0.74"
NOTES = "第一条更新\n第二条更新"
URL = f"{DOWNLOAD_ROOT}/{TAG}/app-release.apk"


class FakeGitee:
    def __init__(self):
        self.version = {"versionName": "1.0.74", "versionCode": version_code_of(TAG),
                        "releaseNotes": NOTES, "downloadUrl": URL}
        self.release = {"assets": [{"name": "app-release.apk", "browser_download_url": URL}]}

    def read_json_file(self, name, allow_missing=False):
        return {"sha": "version-sha"}, json.loads(json.dumps(self.version))

    def request(self, method, path, payload=None, **kwargs):
        if path == "releases/tags/" + TAG:
            return self.release
        raise AssertionError(f"交付校验不该写任何东西，却被调用了 {method} {path}")


class DeliveryTests(unittest.TestCase):
    def test_version_code_matches_release_pipeline_formula(self):
        # 与 scripts/release.sh / release.yml 的 Resolve Version From Tag 必须一致
        self.assertEqual(version_code_of("v1.0.74"), 10074)
        self.assertEqual(version_code_of("v1.2.2"), 10202)
        self.assertEqual(version_code_of("v2.0.0"), 20000)

    def test_matching_release_passes(self):
        verify_delivery(FakeGitee(), TAG, NOTES)

    def test_mismatched_version_or_notes_fails(self):
        for changes in ({"versionCode": 73}, {"versionName": "1.0.73"},
                        {"releaseNotes": "旧日志"}, {"downloadUrl": "https://example.invalid/wrong.apk"}):
            with self.subTest(changes=changes):
                client = FakeGitee()
                client.version.update(changes)
                with self.assertRaises(RuntimeError):
                    verify_delivery(client, TAG, NOTES)

    def test_missing_apk_asset_fails(self):
        client = FakeGitee()
        client.release["assets"] = []
        with self.assertRaises(RuntimeError):
            verify_delivery(client, TAG, NOTES)

    def test_empty_notes_fails(self):
        with self.assertRaises(ValueError):
            verify_delivery(FakeGitee(), TAG, "")

    @patch("verify_delivery.urlopen")
    def test_http_error_never_exposes_token(self, open_url):
        token = "private-test-token"
        open_url.side_effect = HTTPError("https://example.invalid/?access_token=" + token, 403, token, {}, None)
        with self.assertRaises(RuntimeError) as caught:
            Gitee(token).request("GET", "contents/version.json")
        self.assertEqual(str(caught.exception), "Gitee returned HTTP 403")

    @patch("verify_delivery.urlopen")
    def test_transport_error_never_exposes_token(self, open_url):
        open_url.side_effect = URLError("private-test-token")
        with self.assertRaisesRegex(RuntimeError, "^Gitee request failed$"):
            Gitee("private-test-token").request("GET", "contents/version.json")

    def test_token_is_required(self):
        with self.assertRaises(ValueError):
            Gitee("")


if __name__ == "__main__":
    unittest.main()
