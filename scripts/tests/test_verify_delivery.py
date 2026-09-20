import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_delivery import Gitee, verify_delivery

TAG = "v1.2.2"
APK_URL = f"https://gitee.com/Elisoar/hnnu-jiaowu-app/releases/download/{TAG}/app-release.apk"


def latest_release(tag=TAG, assets=None):
    """Gitee 的 releases/latest 除了用户的 APK，还会自动挂 <tag>.zip / <tag>.tar.gz
    两个**源码包** —— 所以"有资产"不等于"有安装包"，必须有挑 .apk 的判断。"""
    if assets is None:
        assets = [
            {"name": "app-release.apk", "browser_download_url": APK_URL},
            {"name": f"{tag}.zip", "browser_download_url": "https://gitee.com/src.zip"},
            {"name": f"{tag}.tar.gz", "browser_download_url": "https://gitee.com/src.tar.gz"},
        ]
    return {"tag_name": tag, "assets": assets}


class FakeGitee:
    def __init__(self, payload=None):
        self.payload = payload
        self.calls = []

    def request(self, method, path, allow_missing=False):
        self.calls.append((method, path))
        return self.payload


class DeliveryTests(unittest.TestCase):
    def test_matching_release_passes_and_returns_apk_url(self):
        client = FakeGitee(latest_release())
        self.assertEqual(verify_delivery(client, TAG), APK_URL)
        # 只读：不能有写请求
        self.assertEqual(client.calls, [("GET", "releases/latest")])

    def test_source_archives_do_not_count_as_installer(self):
        # 只有 zip / tar.gz 时，应用挑不到 .apk —— 这正是"点更新却下到源码包"的成因
        assets = [{"name": f"{TAG}.zip", "browser_download_url": "https://gitee.com/src.zip"},
                  {"name": f"{TAG}.tar.gz", "browser_download_url": "https://gitee.com/src.tgz"}]
        with self.assertRaises(RuntimeError):
            verify_delivery(FakeGitee(latest_release(assets=assets)), TAG)

    def test_no_apk_asset_at_all_fails(self):
        with self.assertRaises(RuntimeError):
            verify_delivery(FakeGitee(latest_release(assets=[])), TAG)

    def test_latest_pointing_at_another_tag_fails(self):
        # 发了 v1.2.2 但 latest 还是 v1.2.1：用户点更新拿到的仍是旧包
        with self.assertRaises(RuntimeError):
            verify_delivery(FakeGitee(latest_release(tag="v1.2.1")), TAG)

    def test_missing_release_fails(self):
        # releases/latest 在仓库还没有任何 Release 时返回 404，客户端按 allow_missing 给 None
        with self.assertRaises(RuntimeError):
            verify_delivery(FakeGitee(None), TAG)

    def test_apk_without_download_url_fails(self):
        assets = [{"name": "app-release.apk"}]
        with self.assertRaises(RuntimeError):
            verify_delivery(FakeGitee(latest_release(assets=assets)), TAG)

    def test_uppercase_apk_suffix_is_accepted(self):
        # 应用侧用的是 endsWith(".apk", ignoreCase = true)，校验不能比它更严
        assets = [{"name": "App-Release.APK", "browser_download_url": APK_URL}]
        self.assertEqual(verify_delivery(FakeGitee(latest_release(assets=assets)), TAG), APK_URL)

    def test_token_is_optional(self):
        # 不带令牌必须能跑：否则"没登录的用户能不能更新到"这件事就永远验不了
        self.assertEqual(Gitee().token, "")
        self.assertEqual(Gitee("").token, "")

    def test_only_get_requests_are_allowed(self):
        with self.assertRaises(AssertionError):
            Gitee("token").request("POST", "releases")

    @patch("verify_delivery.urlopen")
    def test_http_error_never_exposes_token(self, open_url):
        token = "private-test-token"
        open_url.side_effect = HTTPError(
            "https://example.invalid/?access_token=" + token, 403, token, {}, None)
        with self.assertRaises(RuntimeError) as caught:
            Gitee(token).request("GET", "releases/latest")
        self.assertEqual(str(caught.exception), "Gitee returned HTTP 403")

    @patch("verify_delivery.urlopen")
    def test_transport_error_never_exposes_token(self, open_url):
        open_url.side_effect = URLError("private-test-token")
        with self.assertRaisesRegex(RuntimeError, "^Gitee request failed$"):
            Gitee("private-test-token").request("GET", "releases/latest")

    @patch("verify_delivery.urlopen")
    def test_payload_is_parsed(self, open_url):
        class Response:
            def read(self):
                return json.dumps(latest_release()).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        open_url.return_value = Response()
        self.assertEqual(Gitee().request("GET", "releases/latest")["tag_name"], TAG)


if __name__ == "__main__":
    unittest.main()
