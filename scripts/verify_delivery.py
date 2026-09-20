#!/usr/bin/env python3
"""Verify that a release really landed on Gitee: version.json and the APK asset.

发版流程收尾的交付校验。**公告已经不在这里了** —— 公告改为随包内置
（`app/src/main/assets/announcement.json`），跟代码同一次构建、同一个版本号，
发版流程里不再有"把公告推到 Gitee"这一步。这里只回答一个问题：
线上那份 version.json 与本 Release 的 APK 是否与本次 tag 一致。
"""
import argparse
import base64
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

# Gitee 仓库可由环境变量 GITEE_REPO 覆盖（形如 owner/repo），与
# .github/workflows/release.yml 的 sync-to-gitee job 用同一个仓库变量；
# 未设置时回退到本项目仓库。
_GITEE_REPO = os.environ.get("GITEE_REPO") or "Elisoar/hnnu-jiaowu-app"
API_ROOT = f"https://gitee.com/api/v5/repos/{_GITEE_REPO}"
DOWNLOAD_ROOT = f"https://gitee.com/{_GITEE_REPO}/releases/download"


def version_code_of(tag):
    """versionCode = major*10000 + minor*100 + patch（v1.2.2 -> 10202）。

    与 scripts/release.sh 和 .github/workflows/release.yml 的 Resolve Version From Tag
    必须保持一致：三处算出不同的 code，应用内「检查更新」就会给出一个永远装不上的版本。
    """
    major, minor, patch = (int(part) for part in tag.lstrip("vV").split("."))
    return major * 10000 + minor * 100 + patch


class Gitee:
    def __init__(self, token):
        if not token:
            raise ValueError("GITEE_TOKEN is required")
        self.token = token

    def request(self, method, path, payload=None, allow_missing=False):
        data = None
        if method == "GET":
            separator = "&" if "?" in path else "?"
            path += separator + urlencode({"access_token": self.token})
        else:
            data = json.dumps(dict(payload or {}, access_token=self.token)).encode("utf-8")
        request = Request(
            API_ROOT + "/" + path, data=data, method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json",
                     "User-Agent": "academic-assistant-release", "Cache-Control": "no-cache"},
        )
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            # Never print the authenticated URL, response body or token.
            raise RuntimeError(f"Gitee returned HTTP {error.code}") from None
        except URLError:
            raise RuntimeError("Gitee request failed") from None

    def read_json_file(self, name, allow_missing=False):
        metadata = self.request("GET", f"contents/{name}?ref=main", allow_missing=allow_missing)
        if metadata is None:
            return None, None
        if not isinstance(metadata, dict) or metadata.get("encoding") != "base64":
            raise RuntimeError(f"Invalid Gitee file response for {name}")
        content = base64.b64decode(metadata["content"]).decode("utf-8-sig")
        return metadata, json.loads(content)


def verify_delivery(client, tag, notes):
    if not notes.strip():
        raise ValueError("Release notes must not be empty")
    _, version = client.read_json_file("version.json")
    expected_url = f"{DOWNLOAD_ROOT}/{tag}/app-release.apk"
    if not isinstance(version, dict) or (
        version.get("versionName") != tag[1:]
        or version.get("versionCode") != version_code_of(tag)
        or version.get("downloadUrl") != expected_url
        or str(version.get("releaseNotes", "")).strip() != notes.strip()
    ):
        raise RuntimeError("Gitee version.json does not match this release")
    release = client.request("GET", f"releases/tags/{tag}")
    assets = release.get("assets", []) if isinstance(release, dict) else []
    if not any(asset.get("name") == "app-release.apk"
               and asset.get("browser_download_url") == expected_url for asset in assets):
        raise RuntimeError("Gitee release APK is missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    if re.fullmatch(r"v\d+\.\d+\.\d+", args.tag) is None:
        parser.error("tag must have the form vX.Y.Z")
    verify_delivery(
        Gitee(os.environ.get("GITEE_TOKEN", "")),
        args.tag,
        os.environ.get("RELEASE_NOTES", ""),
    )
    print(f"Verified Gitee delivery for {args.tag}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError) as error:
        raise SystemExit(f"ERROR: {error}")
